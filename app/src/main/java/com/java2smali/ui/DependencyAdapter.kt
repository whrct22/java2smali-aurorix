package com.java2smali.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.java2smali.R

class DependencyAdapter : RecyclerView.Adapter<DependencyAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun submit(data: List<String>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_java_file, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = items[position]
        holder.text.isSelected = false
    }

    override fun getItemCount(): Int = items.size

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)
}
