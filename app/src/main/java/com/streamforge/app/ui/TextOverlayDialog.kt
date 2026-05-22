package com.streamforge.app.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.streamforge.app.R
import com.streamforge.app.databinding.DialogTextOverlayBinding
import com.streamforge.app.overlay.OverlayItem
import java.util.UUID

/**
 * Phase 5B + 7 polish: dialog for creating or editing a text overlay.
 * User picks text content, font size (12–96 sp via slider OR numeric input),
 * and a color (12 preset swatches OR custom hex code).
 */
class TextOverlayDialog : DialogFragment() {

    private var existing: OverlayItem.Text? = null
    private var onResult: ((OverlayItem.Text) -> Unit)? = null
    private var selectedColor: Int = Color.WHITE
    private val swatches = mutableListOf<View>()

    private val presetColors = intArrayOf(
        Color.WHITE,
        Color.BLACK,
        Color.parseColor("#E53935"),
        Color.parseColor("#FB8C00"),
        Color.parseColor("#FDD835"),
        Color.parseColor("#43A047"),
        Color.parseColor("#1E88E5"),
        Color.parseColor("#8E24AA"),
        Color.parseColor("#EC407A"),
        Color.parseColor("#00ACC1"),
        Color.parseColor("#6D4C41"),
        Color.parseColor("#757575")
    )

    fun setExisting(item: OverlayItem.Text) {
        existing = item
    }

    fun setOnResult(callback: (OverlayItem.Text) -> Unit) {
        onResult = callback
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogTextOverlayBinding.inflate(LayoutInflater.from(requireContext()))
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        selectedColor = existing?.colorArgb ?: Color.WHITE

        existing?.let { item ->
            binding.etText.setText(item.text)
            binding.sliderFontSize.value = item.fontSizeSp.coerceIn(12f, 96f)
            binding.etFontSize.setText(item.fontSizeSp.toInt().toString())
        }

        // Pre-fill hex field from the current color.
        binding.etHex.setText(formatHex(selectedColor))

        bindFontSize(binding)
        buildSwatches(binding, density)
        bindHexInput(binding)

        return AlertDialog.Builder(ctx)
            .setTitle(R.string.text_overlay_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = binding.etText.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.default_text_overlay)
                val finalSize = binding.sliderFontSize.value
                val result = OverlayItem.Text(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    text = typed,
                    fontSizeSp = finalSize,
                    colorArgb = selectedColor,
                    x = existing?.x ?: 0.5f,
                    y = existing?.y ?: 0.5f,
                    scale = existing?.scale ?: 1f,
                    rotation = existing?.rotation ?: 0f,
                    zIndex = existing?.zIndex ?: 0,
                    visible = existing?.visible ?: true
                )
                onResult?.invoke(result)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    /** Keep the numeric input box and the slider in sync; both clamp to 12..96. */
    private fun bindFontSize(binding: DialogTextOverlayBinding) {
        var updatingFromCode = false

        binding.sliderFontSize.addOnChangeListener { _, value, _ ->
            if (updatingFromCode) return@addOnChangeListener
            updatingFromCode = true
            binding.etFontSize.setText(value.toInt().toString())
            updatingFromCode = false
        }

        binding.etFontSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromCode) return
                val n = s?.toString()?.toIntOrNull() ?: return
                val clamped = n.coerceIn(12, 96)
                updatingFromCode = true
                if (binding.sliderFontSize.value.toInt() != clamped) {
                    binding.sliderFontSize.value = clamped.toFloat()
                }
                updatingFromCode = false
            }
        })
    }

    private fun buildSwatches(binding: DialogTextOverlayBinding, density: Float) {
        val swatchSize = (40 * density).toInt()
        val swatchMargin = (6 * density).toInt()
        val swatchBorder = (2 * density).toInt()

        binding.colorGrid.removeAllViews()
        swatches.clear()
        presetColors.forEach { color ->
            val swatch = View(requireContext()).apply {
                background = makeSwatch(color, color == selectedColor, swatchBorder)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = swatchSize
                    height = swatchSize
                    setMargins(swatchMargin, swatchMargin, swatchMargin, swatchMargin)
                }
                contentDescription = formatHex(color)
                setOnClickListener {
                    selectedColor = color
                    refreshSwatches(swatchBorder)
                    binding.etHex.setText(formatHex(color))
                    binding.tilHex.error = null
                }
                tag = color
            }
            swatches.add(swatch)
            binding.colorGrid.addView(swatch)
        }
    }

    private fun bindHexInput(binding: DialogTextOverlayBinding) {
        val density = resources.displayMetrics.density
        val swatchBorder = (2 * density).toInt()
        binding.etHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    binding.tilHex.error = null
                    return
                }
                val parsed = parseHexColor(raw)
                if (parsed == null) {
                    binding.tilHex.error = getString(R.string.custom_hex_color_invalid)
                } else {
                    binding.tilHex.error = null
                    selectedColor = parsed
                    refreshSwatches(swatchBorder)
                }
            }
        })
    }

    private fun refreshSwatches(borderPx: Int) {
        swatches.forEach { view ->
            val c = view.tag as Int
            view.background = makeSwatch(c, c == selectedColor, borderPx)
        }
    }

    private fun makeSwatch(color: Int, selected: Boolean, borderPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                if (selected) borderPx * 2 else borderPx,
                if (selected) Color.parseColor("#FFA726") else Color.parseColor("#666666")
            )
        }
    }

    private fun parseHexColor(input: String): Int? {
        val s = if (input.startsWith("#")) input else "#$input"
        // Accept #RRGGBB or #AARRGGBB
        val body = s.drop(1)
        if (body.length != 6 && body.length != 8) return null
        if (!body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return try {
            Color.parseColor(s)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatHex(color: Int): String = "#%08X".format(color)

    companion object {
        const val TAG = "TextOverlayDialog"
    }
}
