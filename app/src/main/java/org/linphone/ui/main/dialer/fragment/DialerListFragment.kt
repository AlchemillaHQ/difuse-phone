/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.dialer.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.DialerListFragmentBinding
import org.linphone.ui.main.dialer.viewmodel.DialerListViewModel
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.utils.Event

@UiThread
class DialerListFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Dialer List Fragment]"
    }

    private lateinit var binding: DialerListFragmentBinding

    private lateinit var listViewModel: DialerListViewModel

    private val backspaceHandler = Handler(Looper.getMainLooper())

    private var backspaceLongPressing = false

    private var backspaceRepeatDelay = 400L

    private var updatingFromViewModel = false

    private val phoneNumberFilter = InputFilter { source, start, end, _, _, _ ->
        val filtered = StringBuilder()
        for (i in start until end) {
            val c = source[i]
            if (c.isDigit() || c == '*' || c == '+' || c == '#') {
                filtered.append(c)
            }
        }
        if (filtered.length == end - start) null else filtered.toString()
    }

    override fun onDefaultAccountChanged() {
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        return AnimationUtils.loadAnimation(activity, R.anim.hold)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialerListFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listViewModel = ViewModelProvider(this)[DialerListViewModel::class.java]

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = listViewModel
        binding.model = listViewModel.numpadModel

        binding.setAddToContactsClickListener {
            val number = listViewModel.digits.value.orEmpty().trim()
            if (number.isNotEmpty()) {
                Log.i("$TAG Navigating to new contact with pre-filled value [$number]")
                sharedViewModel.sipAddressToAddToNewContact = number
                sharedViewModel.navigateToContactsEvent.value = Event(true)
            }
        }

        listViewModel.digits.observe(viewLifecycleOwner) { digits ->
            updateTextSize(digits?.length ?: 0)

            val current = binding.displayNumber.text?.toString().orEmpty()
            if (current != digits.orEmpty()) {
                updatingFromViewModel = true
                binding.displayNumber.setText(digits.orEmpty())
                binding.displayNumber.setSelection(digits.orEmpty().length)
                updatingFromViewModel = false
            }

           binding.displayNumberScroll.post {
            val child = binding.displayNumber
            val scrollWidth = binding.displayNumberScroll.width
            val textWidth = child.width

            if (textWidth > scrollWidth) {
                binding.displayNumberScroll.smoothScrollTo(
                    textWidth - scrollWidth,
                    0
                )
            } else {
                binding.displayNumberScroll.scrollTo(0, 0)
            }
        }
        }

        observeToastEvents(listViewModel)

        binding.displayNumber.filters = arrayOf(phoneNumberFilter)

        binding.displayNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (updatingFromViewModel) return
                val text = s?.toString().orEmpty()
                if (text != listViewModel.digits.value) {
                    listViewModel.setDigitsFromPaste(text)
                }
            }
        })

        binding.displayNumber.showSoftInputOnFocus = false
        binding.displayNumber.onFocusChangeListener = OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val imm = ContextCompat.getSystemService(v.context, InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }

        binding.backspace.setOnLongClickListener {
            startBackspaceRepeat()
            true
        }

        binding.backspace.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    stopBackspaceRepeat()
                }
            }
            false
        }

        listViewModel.title.value = getString(R.string.bottom_navigation_dialer_label)

        setViewModel(listViewModel)
        initViews(
            binding.slidingPaneLayout,
            binding.topBar,
            binding.bottomNavBar,
            R.id.dialerListFragment
        )
    }

    override fun onResume() {
        super.onResume()
        binding.displayNumber.clearFocus()
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.displayNumber.windowToken, 0)
    }

    private fun startBackspaceRepeat() {
        backspaceLongPressing = true
        backspaceRepeatDelay = 400L
        scheduleBackspaceRepeat()
    }

    private fun stopBackspaceRepeat() {
        backspaceLongPressing = false
        backspaceHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleBackspaceRepeat() {
        backspaceHandler.postDelayed({
            listViewModel.removeLastDigit()
            backspaceRepeatDelay = maxOf(50L, backspaceRepeatDelay - 50L)
            if (backspaceLongPressing) {
                scheduleBackspaceRepeat()
            }
        }, backspaceRepeatDelay)
    }

   private fun updateTextSize(digitCount: Int) {
    val size = when {
        digitCount <= 4 -> 52f
        digitCount <= 8 -> 48f
        digitCount <= 12 -> 42f
        digitCount <= 16 -> 36f
        digitCount <= 22 -> 32f
        digitCount <= 28 -> 28f
        else -> 24f
    }

    binding.displayNumber.setTextSize(
        TypedValue.COMPLEX_UNIT_SP,
        size
    )
}
}
