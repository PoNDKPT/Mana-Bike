package com.example.mana_bike_v001

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class TransactionAdapter : ListAdapter<TransactionItem, TransactionAdapter.VH>(DIFF) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.tvDate)
        val bank: TextView = view.findViewById(R.id.tvBank)
        val amount: TextView = view.findViewById(R.id.tvAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.date.text = item.formattedDate
        val bankLabel = Bank.values().firstOrNull { it.key == item.bank }?.label
        holder.bank.text = bankLabel.orEmpty()
        holder.bank.visibility = if (bankLabel == null) View.GONE else View.VISIBLE
        holder.amount.text = "+" + Fmt.money(item.amount)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TransactionItem>() {
            override fun areItemsTheSame(a: TransactionItem, b: TransactionItem) = a.id == b.id
            override fun areContentsTheSame(a: TransactionItem, b: TransactionItem) = a == b
        }
    }
}
