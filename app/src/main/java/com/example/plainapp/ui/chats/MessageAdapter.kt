package com.example.plainapp.ui.chats

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.plainapp.ChatActivity
import com.example.plainapp.data.Message
import com.example.plainapp.databinding.MessageViewBinding
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter


class MessageAdapter(private val chatActivity: ChatActivity) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>(), View.OnClickListener {

    private var data: List<Message> = emptyList()

    class MessageViewHolder(val binding: MessageViewBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = MessageViewBinding.inflate(inflater, parent, false)

        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = data[position]

        val binding = holder.binding

        val linearLayoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)

        if (message.createdBy != chatActivity.myUser!!.id) {

            linearLayoutParams.gravity = Gravity.START
            binding.loadImg.visibility = View.INVISIBLE

        } else {

            linearLayoutParams.gravity = Gravity.END
            binding.loadImg.visibility = View.VISIBLE

        }

        binding.message.layoutParams = linearLayoutParams
        binding.messagePlate.layoutParams = linearLayoutParams

        val time = LocalDateTime.ofInstant(Instant.parse(message.createdAt), OffsetDateTime.now().offset)
            .format(DateTimeFormatter.ofPattern("HH:mm"))

        binding.message.text = message.body
        binding.time.text = time

        binding.root.setOnClickListener(this)

        holder.itemView.tag = message

    }

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: List<Message>) {
        data = newData
        notifyDataSetChanged()
    }

    override fun onClick(view: View) {

        //val message = view.tag as Message



    }

}