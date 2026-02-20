package com.java2smali.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.java2smali.R

class DependencyClassAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<DependencyClassAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun submit(data: List<String>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_java_file, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.txtFileName)
    }
}
