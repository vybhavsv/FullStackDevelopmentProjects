package com.vybhav.karnatakavehiclevalidation.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vybhav.karnatakavehiclevalidation.data.PucRecord
import com.vybhav.karnatakavehiclevalidation.databinding.ItemRecordBinding

class ResultAdapter : RecyclerView.Adapter<ResultAdapter.ResultViewHolder>() {
    private val items = mutableListOf<PucRecord>()

    fun submitList(records: List<PucRecord>) {
        items.clear()
        items.addAll(records)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ResultViewHolder(private val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: PucRecord) {
            binding.puccNumberText.text = record.puccNumber.ifBlank { record.registrationNumber }
            binding.stationText.text = record.make.ifBlank { "Vehicle details available" }
            binding.registrationText.text = record.registrationNumber.ifBlank { "Registration not listed" }
            binding.makeText.text = record.category.ifBlank { "Make not listed" }
            binding.resultChipText.text = record.result.ifBlank { "Available" }
            binding.resultChipText.setTextColor(
                when (record.result.trim().uppercase()) {
                    "PASS" -> Color.parseColor("#0F766E")
                    "FAIL" -> Color.parseColor("#B42318")
                    else -> Color.parseColor("#155E4B")
                }
            )
            binding.detailText.text = buildString {
                appendLine("Reg Date: ${record.registrationDate}")
                appendLine("Test Date: ${record.testDate} ${record.testTime}".trim())
                appendLine()
                appendLine("Valid Date: ${record.validDate}")
                appendLine()
                appendLine("HSU Mean: ${record.hsuMean}")
                appendLine("K Mean: ${record.kMean}")
                appendLine()
                appendLine("Oil Temp: ${record.oilTempMean}")
                appendLine("RPM Max: ${record.rpmMaxMean}")
                appendLine()
                appendLine("RPM Min: ${record.rpmMinMean}")
                append("Cancelled: ${record.cancelled}")
            }

            if (record.detailsUrl.isNotBlank()) {
                binding.root.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(record.detailsUrl))
                    binding.root.context.startActivity(intent)
                }
            } else {
                binding.root.setOnClickListener(null)
            }
        }
    }
}
