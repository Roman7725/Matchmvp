package com.matchmvp.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

data class UiPeer(
    val uid: String,
    val avatarLabel: String,
    val liked: Boolean = false,
    val hasBadge: Boolean = false
)

class PeerAdapter(
    private val onLikeClicked: (UiPeer) -> Unit
) : ListAdapter<UiPeer, PeerAdapter.PeerViewHolder>(PeerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Используем строго R.id.avatarText и R.id.likeBtn из вашего item_peer.xml
        private val avatarText: TextView = itemView.findViewById(R.id.avatarText)
        private val likeBtn: Button = itemView.findViewById(R.id.likeBtn)

        fun bind(peer: UiPeer) {
            avatarText.text = if (peer.hasBadge) {
                "⭐ ${peer.avatarLabel}"
            } else {
                peer.avatarLabel
            }

            likeBtn.text = if (peer.liked) "Лайк отправлен" else "Лайк"
            likeBtn.isEnabled = !peer.liked

            likeBtn.setOnClickListener {
                // Используем adapterPosition для совместимости со старыми версиями RecyclerView
                @Suppress("DEPRECATION")
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onLikeClicked(peer)
                }
            }
        }
    }

    private class PeerDiffCallback : DiffUtil.ItemCallback<UiPeer>() {
        override fun areItemsTheSame(oldItem: UiPeer, newItem: UiPeer): Boolean {
            return oldItem.uid == newItem.uid
        }

        override fun areContentsTheSame(oldItem: UiPeer, newItem: UiPeer): Boolean {
            return oldItem == newItem
        }
    }
}
