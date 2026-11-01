/*
 * Copyright (C) 2021 Chaldeaprjkt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.gamebar

import android.content.Context
import android.graphics.Point
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

class DraggableTouchListener(
    context: Context,
    private val view: View,
    private val initialPosition: () -> Point,
    private val positionListener: (x: Int, y: Int) -> Unit,
    private val onDragComplete: () -> Unit
) : View.OnTouchListener {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointerStartX = 0
    private var pointerStartY = 0
    private var initialX = 0
    private var initialY = 0
    private var moving = false

    init {
        view.setOnTouchListener(this)
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerStartX = motionEvent.rawX.toInt()
                pointerStartY = motionEvent.rawY.toInt()
                with(initialPosition()) {
                    initialX = x
                    initialY = y
                }
                moving = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = motionEvent.rawX - pointerStartX
                val deltaY = motionEvent.rawY - pointerStartY
                if (moving || hypot(deltaX, deltaY) > touchSlop) {
                    positionListener(initialX + deltaX.toInt(), initialY + deltaY.toInt())
                    moving = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (moving) {
                    onDragComplete()
                } else {
                    view.performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (moving) {
                    onDragComplete()
                }
                moving = false
            }
        }
        return true
    }
}
