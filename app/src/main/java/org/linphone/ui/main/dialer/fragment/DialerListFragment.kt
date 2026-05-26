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

import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
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
                sharedViewModel.showNewContactEvent.value = Event(true)
            }
        }

        listViewModel.digits.observe(viewLifecycleOwner) { digits ->
            updateTextSize(digits?.length ?: 0)
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
        digitCount <= 3 -> 52f
        digitCount <= 6 -> 46f
        digitCount <= 9 -> 40f
        digitCount <= 12 -> 34f
        digitCount <= 16 -> 28f
        digitCount <= 20 -> 24f
        else -> 20f
    }

    binding.displayNumber.setTextSize(
        TypedValue.COMPLEX_UNIT_SP,
        size
    )
}
}
