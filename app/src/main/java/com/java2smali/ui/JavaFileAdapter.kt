package com.java2smali.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.java2smali.R

class JavaFileAdapter(
    private val onClick: (JavaSourceFileUi) -> Unit,
    private val onLongClick: (JavaSourceFileUi) -> Unit
) : RecyclerView.Adapter<JavaFileAdapter.VH>() {

    private val items = mutableListOf<JavaSourceFileUi>()
    private var activePath: String? = null

    fun submit(files: List<JavaSourceFileUi>, active: String?) {
        items.clear()
        items.addAll(files)
        activePath = active
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
        val prefix = when (item.type) {
            EntryType.WORKSPACE -> "[WS] "
            EntryType.FOLDER -> "[DIR] "
            EntryType.FILE -> ""
        }
        holder.text.text = prefix + item.name
        val basePadding = holder.itemView.resources.displayMetrics.density.let { (it * 12).toInt() }
        val extraIndent = holder.itemView.resources.displayMetrics.density.let { (it * 14).toInt() } * item.depth
        holder.text.setPadding(basePadding + extraIndent, holder.text.paddingTop, holder.text.paddingRight, holder.text.paddingBottom)
        holder.itemView.isSelected = item.path == activePath
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
