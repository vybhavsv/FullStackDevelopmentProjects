package com.vybhav.karnatakavehiclevalidation

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.vybhav.karnatakavehiclevalidation.data.VehicleLookupRepository
import com.vybhav.karnatakavehiclevalidation.data.VehicleLookupResult
import com.vybhav.karnatakavehiclevalidation.databinding.ActivityMainBinding
import com.vybhav.karnatakavehiclevalidation.ui.ResultAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val repository = VehicleLookupRepository()
    private val resultAdapter = ResultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.resultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = resultAdapter
        }

        binding.checkButton.setOnClickListener { performLookup() }
        binding.registrationInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performLookup()
                true
            } else {
                false
            }
        }

        setIdleState()
    }

    private fun performLookup() {
        val input = binding.registrationInput.text?.toString().orEmpty()
        val normalized = input.uppercase().filter { it.isLetterOrDigit() }
        if (normalized.isBlank()) {
            binding.registrationLayout.error = "Please enter a vehicle registration number"
            return
        }

        binding.registrationLayout.error = null
        showLoading(true)

        lifecycleScope.launch {
            runCatching { repository.lookupVehicle(normalized) }
                .onSuccess { result -> renderLookupResult(result) }
                .onFailure {
                    binding.summaryCard.isVisible = true
                    binding.statusText.text = getString(R.string.status_error)
                    binding.matchedFuelText.text = "-"
                    binding.recordCountText.text = "0"
                    binding.validDateText.text = "-"
                    binding.messageText.text = getString(R.string.status_error)
                    resultAdapter.submitList(emptyList())
                    Toast.makeText(this@MainActivity, it.message ?: getString(R.string.status_error), Toast.LENGTH_LONG).show()
                }
            showLoading(false)
        }
    }

    private fun renderLookupResult(result: VehicleLookupResult) {
        binding.summaryCard.isVisible = true
        binding.statusText.text = if (result.hasResults) {
            "Matched after checking ${result.checkedFuelTypes.joinToString(" -> ") { fuel -> fuel.name.lowercase().replaceFirstChar(Char::titlecase) }}"
        } else {
            getString(R.string.status_no_match)
        }
        binding.matchedFuelText.text = result.matchedFuelType?.name?.lowercase()?.replaceFirstChar(Char::titlecase) ?: "No match"
        binding.recordCountText.text = result.records.size.toString()
        binding.validDateText.text = result.records.firstOrNull()?.validDate?.ifBlank { "-" } ?: "-"
        binding.messageText.text = if (result.hasResults) {
            "Showing results for ${result.normalizedRegistration}"
        } else {
            getString(R.string.status_no_match)
        }
        resultAdapter.submitList(result.records)
    }

    private fun setIdleState() {
        binding.summaryCard.isVisible = false
        binding.progressBar.isVisible = false
        binding.messageText.text = getString(R.string.status_idle)
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.isVisible = isLoading
        binding.checkButton.isEnabled = !isLoading
        binding.messageText.text = if (isLoading) getString(R.string.status_loading) else getString(R.string.status_idle)
    }
}
