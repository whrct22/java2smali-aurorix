package com.java2smali.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.java2smali.R
import com.java2smali.deps.DependencyManager

class DependencyFileAdapter(
    private val onClick: (DependencyManager.DependencyFilePreview) -> Unit,
    private val onLongClick: (DependencyManager.DependencyFilePreview) -> Unit
) : RecyclerView.Adapter<DependencyFileAdapter.VH>() {

    private val items = mutableListOf<DependencyManager.DependencyFilePreview>()

    fun submit(data: List<DependencyManager.DependencyFilePreview>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dependency_file, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.primary.text = item.fileName
        val countText = holder.itemView.context.getString(R.string.dependency_file_count, item.classCount)
        holder.secondary.text = if (item.isBuiltin) {
            "$countText · 内置(不可删除)"
        } else {
            countText
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val primary: TextView = view.findViewById(R.id.txtPrimary)
        val secondary: TextView = view.findViewById(R.id.txtSecondary)
    }
}
