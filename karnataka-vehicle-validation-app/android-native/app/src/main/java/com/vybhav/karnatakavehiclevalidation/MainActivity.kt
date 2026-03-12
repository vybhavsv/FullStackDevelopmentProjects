package com.vybhav.karnatakavehiclevalidation

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        applyWindowInsets()

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
        showDisclaimerIfNeeded()
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
                .onFailure { throwable ->
                    binding.summaryCard.isVisible = true
                    binding.statusText.text = getString(R.string.status_error)
                    binding.matchedFuelText.text = "-"
                    binding.recordCountText.text = "0"
                    binding.validDateText.text = "-"
                    binding.messageText.isVisible = true
                    binding.messageText.text = getString(R.string.status_error)
                    resultAdapter.submitList(emptyList())
                    Toast.makeText(this@MainActivity, throwable.message ?: getString(R.string.status_error), Toast.LENGTH_LONG).show()
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
        binding.messageText.isVisible = false
        resultAdapter.submitList(result.records)
    }

    private fun setIdleState() {
        binding.summaryCard.isVisible = false
        binding.progressBar.isVisible = false
        binding.messageText.isVisible = true
        binding.messageText.text = getString(R.string.status_idle)
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.isVisible = isLoading
        binding.checkButton.isEnabled = !isLoading
        if (isLoading) {
            binding.messageText.isVisible = true
            binding.messageText.text = getString(R.string.status_loading)
        } else if (!binding.summaryCard.isVisible && resultAdapter.itemCount == 0) {
            binding.messageText.isVisible = true
            binding.messageText.text = getString(R.string.status_idle)
        }
    }

    private fun showDisclaimerIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HIDE_DISCLAIMER, false)) {
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_disclaimer, null)
        val messageView = dialogView.findViewById<TextView>(R.id.disclaimerMessage)
        val checkbox = dialogView.findViewById<CheckBox>(R.id.disclaimerCheckbox)
        messageView.text = getString(R.string.disclaimer_message)
        messageView.movementMethod = LinkMovementMethod.getInstance()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_title)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_ok) { dialog, _ ->
                if (checkbox.isChecked) {
                    prefs.edit().putBoolean(KEY_HIDE_DISCLAIMER, true).apply()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun applyWindowInsets() {
        val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val bottomPadding = (16 * resources.displayMetrics.density).toInt()
        val topPadding = (16 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                horizontalPadding,
                topPadding + statusBars.top,
                horizontalPadding,
                bottomPadding,
            )
            insets
        }
    }

    companion object {
        private const val PREFS_NAME = "ka_vehicle_puc_prefs"
        private const val KEY_HIDE_DISCLAIMER = "hide_disclaimer"
    }
}
