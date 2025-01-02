package com.example.plainapp

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.example.plainapp.data.Chat
import com.example.plainapp.data.ChatRepository
import com.example.plainapp.data.LocalDatabase
import com.example.plainapp.data.Message
import com.example.plainapp.data.User
import com.example.plainapp.data.observeOnce
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.EmptyCoroutineContext

class SocketService : LifecycleService() {

    private val scope = CoroutineScope( EmptyCoroutineContext )
    private lateinit var db: LocalDatabase
    private lateinit var mSocket: Socket
    private lateinit var repository: ChatRepository

    var userLiveData: MutableLiveData<User?> = MutableLiveData<User?>()

    fun logIn(phoneNumber: String) {

        mSocket.emit("userByPN", phoneNumber)
        mSocket.once("userByPN") { args -> scope.launch {

            val user = Json.decodeFromString<User>(args[0].toString())
            scope.launch { userLiveData.postValue(user) }.join()

            Log.d("debug", "Get user: $user")

            val file = File(filesDir, userFileName)
            if (!file.exists()) file.createNewFile()
            else if (file.isDirectory) {
                file.delete()
                file.createNewFile()
            }
            FileOutputStream(file).write(Json.encodeToString(user).toByteArray())

            signIn()

        } }
    }

    private fun signIn() {

        Log.d("debug", "SignIn")

        mSocket.emit("signin", userLiveData.value!!.id)
        mSocket.once("signin") { signInArgs ->
            val result = signInArgs[0] as String
            if (result == "OK") {

                Log.d("debug", "SignIn OK")

                updateAll()

            } else {

                Log.d("debug", "SignIn error")

                signIn()

            }
        }

    }

    private fun updateAll() {

        updateChats()

    }

    var updatingChatsStatus = MutableLiveData(false)

    private fun updateChats() {

        if (updatingChatsStatus.value == true) {

            Log.d("debug", "Updating chats: tried to invoke new call")

        }

        updatingChatsStatus.postValue(true)

        Log.d("debug", "Updating chats: started")

        mSocket.emit("myChats")
        mSocket.once("myChats") { myChatsArgs ->

            Log.d("debug", "Updating chats: get chats")

            val chats = Json.decodeFromString<List<Chat>>(myChatsArgs[0].toString())

            val userIds = mutableListOf<Long>()
            for (chat in chats) {
                when (userLiveData.value!!.id) {
                    chat.participant1 -> {
                        userIds.add(chat.participant2)
                    }
                    chat.participant2 -> {
                        userIds.add(chat.participant1)
                    }
                    else -> { throw Exception("Invalid chat: $chat") }
                }
            }

            mSocket.emit("getUsers", JSONArray(userIds))
            mSocket.once("getUsers") { getUsersArgs -> scope.launch {

                Log.d("debug", "Updating chats: get users")

                scope.launch { repository.addUsers(Json.decodeFromString<List<User>>(getUsersArgs[0].toString())) }.join()

                scope.launch { withContext(Dispatchers.Main) {
                    repository.readAllChats.observeOnce(this@SocketService) { localChats -> scope.launch {

                        Log.d("debug", "Updating chats: get local chats")

                        val chatsToDelete = emptyList<Chat>().toMutableList()
                        for (lChat in localChats) {
                            if (!chats.contains(lChat))
                                chatsToDelete += lChat
                        }

                        if (chatsToDelete.isNotEmpty()) { scope.launch { repository.deleteChats(chatsToDelete) }.join() }

                        val chatsToAdd = emptyList<Chat>().toMutableList()
                        for (chat in chats) {
                            if (!localChats.contains(chat))
                                chatsToAdd += chat
                        }

                        if (chatsToAdd.isNotEmpty()) { scope.launch { repository.addChats(chatsToAdd) }.join() }

                        updatingChatsStatus.postValue(false)

                        Log.d("debug", "Updating chats: finished")

                    } }
                } }


            } }

        }

    }

    fun sendMessage(chatId: Long, body: String) {

        if (userLiveData.value == null) return

        val jsonObj = JSONObject()
        jsonObj.put("body", body)
        Log.d("debug", "Sending chatMessage $jsonObj")
        mSocket.emit("chatMessage", chatId.toString(), jsonObj)

        mSocket.once("chatMessageId") { chatMessageIdArgs ->

            Log.d("debug", "Get chatMessageId ${chatMessageIdArgs[0]}")

            val message = Message(
                id = (chatMessageIdArgs[0] as String).toLong(),
                body = body,
                createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                updatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                createdBy = userLiveData.value!!.id
            )
            scope.launch {
                scope.launch { repository.addChatMessage(chatId, message) }.join()
                Log.d("debug", "My chat message $message in $chatId chat")
            }

        }

    }

    var connectedStatus = MutableLiveData(false)

    private val userFileName = "user.json"

    override fun onCreate() {
        super.onCreate()

        db = LocalDatabase.getDatabase(this)

        repository = ChatRepository(LocalDatabase.getDatabase(application).chatDao())

        mSocket = IO.socket("http://plainapp.ru:3000")

        userLiveData.observe(this) { user -> scope.launch {
            if (user != null) repository.addUser(userLiveData.value!!)
        } }

        val file = File(filesDir, userFileName)
        if (file.exists() && file.isFile) {
            val data = FileInputStream(file).bufferedReader().readText()
            userLiveData.value = Json.decodeFromString<User>(data)
        }

        mSocket.on(Socket.EVENT_CONNECT) {

            Log.d("debug", "SocketIO connected")

            scope.launch { connectedStatus.postValue(true) }

            if (userLiveData.value != null) signIn()

        }

        mSocket.on(Socket.EVENT_DISCONNECT) {

            Log.d("debug", "SocketIO disconnected")

            scope.launch { connectedStatus.postValue(false) }

        }

        mSocket.on(Socket.EVENT_CONNECT_ERROR) { err ->

            Log.d("debug", "SocketIO connection error: $err")

            mSocket.connect()

        }

        //MESSAGES LISTENER
        mSocket.on("chatMessage") { chatMessageArgs ->

            val chatId = when (chatMessageArgs[0].javaClass) {
                Long.Companion::class.java -> chatMessageArgs[0] as Long
                Int.Companion::class.java -> (chatMessageArgs[0] as Int).toLong()
                String.Companion::class.java -> (chatMessageArgs[0] as String).toLong()
                else -> chatMessageArgs[0].toString().toLong()
            }

            val message = Json.decodeFromString<Message>(chatMessageArgs[1].toString())

            scope.launch {
                scope.launch { repository.addChatMessage(chatId, message) }.join()
                Log.d("debug", "New chat message $message in $chatId chat")
            }

        }

        //ERROR LISTENER
        mSocket.on("error") { errorArgs ->

            Log.d("debug", "SocketIO server error: $errorArgs")

        }

        mSocket.connect()

        Log.d("debug", "Service onCreate()")

    }

    override fun onDestroy() {

        mSocket.disconnect()
        scope.cancel()

        Log.d("debug", "Service onDestroy()")

        super.onDestroy()
    }

    private val binder: Binder = MyBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class MyBinder : Binder() {
        val service: SocketService get() = this@SocketService
    }

}