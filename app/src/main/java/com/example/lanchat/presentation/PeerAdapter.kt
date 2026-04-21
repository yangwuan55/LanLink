package com.example.lanchat.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.ymr.lancomm.domain.model.PeerInfo

class PeerAdapter(
    private val onPeerClick: (PeerInfo) -> Unit
) : BaseAdapter() {

    private var peers: List<PeerInfo> = emptyList()

    fun submitList(newPeers: List<PeerInfo>) {
        peers = newPeers
        notifyDataSetChanged()
    }

    override fun getCount(): Int = peers.size

    override fun getItem(position: Int): PeerInfo = peers[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)

        val textView = view.findViewById<TextView>(android.R.id.text1)
        val peer = getItem(position)
        textView.text = peer.name

        view.setOnClickListener {
            onPeerClick(peer)
        }

        return view
    }
}