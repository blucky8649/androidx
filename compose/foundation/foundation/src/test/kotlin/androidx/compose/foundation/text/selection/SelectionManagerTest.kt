/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.compose.foundation.text.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
class SelectionManagerTest {
    private val selectionRegistrar = spy(SelectionRegistrarImpl())
    private val selectable = FakeSelectable()
    private val selectableId = 1L
    private val selectionManager = SelectionManager(selectionRegistrar)

    private val containerLayoutCoordinates = mock<LayoutCoordinates> {
        on { isAttached } doReturn true
    }

    private val startSelectableId = 2L
    private val startSelectable = mock<Selectable> {
        whenever(it.selectableId).thenReturn(startSelectableId)
    }

    private val endSelectableId = 3L
    private val endSelectable = mock<Selectable> {
        whenever(it.selectableId).thenReturn(endSelectableId)
    }

    private val middleSelectableId = 4L
    private val middleSelectable = mock<Selectable> {
        whenever(it.selectableId).thenReturn(middleSelectableId)
    }

    private val lastSelectableId = 5L
    private val lastSelectable = mock<Selectable> {
        whenever(it.selectableId).thenReturn(lastSelectableId)
    }

    private val startCoordinates = Offset(3f, 30f)
    private val endCoordinates = Offset(3f, 600f)

    private val fakeSelection =
        Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = 0,
                selectableId = startSelectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = 5,
                selectableId = endSelectableId
            )
        )

    private val hapticFeedback = mock<HapticFeedback>()
    private val clipboardManager = mock<ClipboardManager>()
    private val textToolbar = mock<TextToolbar>()

    @Before
    fun setup() {
        selectable.clear()
        selectable.selectableId = selectableId
        selectionRegistrar.subscribe(selectable)
        selectionRegistrar.subselections = mapOf(
            selectableId to fakeSelection,
            startSelectableId to fakeSelection,
            endSelectableId to fakeSelection
        )
        selectionManager.containerLayoutCoordinates = containerLayoutCoordinates
        selectionManager.hapticFeedBack = hapticFeedback
        selectionManager.clipboardManager = clipboardManager
        selectionManager.textToolbar = textToolbar
        selectionManager.selection = fakeSelection
    }

    @Test
    fun updateSelection_sorting() {
        selectionManager.updateSelection(
            startHandlePosition = startCoordinates,
            endHandlePosition = endCoordinates,
            previousHandlePosition = null,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        verify(selectionRegistrar, times(1)).sort(containerLayoutCoordinates)
    }

    @Test
    fun updateSelection_single_selectable_calls_getSelection_once() {
        val newSelection = fakeSelection.copy(
            start = fakeSelection.start.copy(
                offset = fakeSelection.start.offset + 1
            )
        )

        selectable.selectionToReturn = newSelection

        selectionManager.updateSelection(
            startHandlePosition = startCoordinates,
            endHandlePosition = endCoordinates,
            previousHandlePosition = null,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        assertThat(selectable.getSelectionCalledTimes).isEqualTo(1)
        assertThat(selectable.lastStartHandlePosition).isEqualTo(startCoordinates)
        assertThat(selectable.lastEndHandlePosition).isEqualTo(endCoordinates)
        assertThat(selectable.lastContainerLayoutCoordinates)
            .isEqualTo(selectionManager.requireContainerCoordinates())
        assertThat(selectable.lastAdjustment).isEqualTo(SelectionAdjustment.None)
        assertThat(selectable.lastPreviousSelection).isEqualTo(fakeSelection)

        verify(
            hapticFeedback,
            times(1)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    @Test
    fun updateSelection_multiple_selectables_calls_getSelection_multiple_times() {
        val anotherSelectableId = 100L
        val selectableAnother = mock<Selectable>()
        whenever(selectableAnother.selectableId).thenReturn(anotherSelectableId)
        whenever(
            selectableAnother.updateSelection(
                anyOffset(), anyOffset(), anyOffset(), any(), any(), any(), any()
            )
        ).thenReturn(Pair(null, false))
        selectionRegistrar.subscribe(selectableAnother)
        selectionRegistrar.subselections = mapOf(
            anotherSelectableId to fakeSelection,
            selectableId to fakeSelection
        )

        selectionManager.updateSelection(
            startHandlePosition = startCoordinates,
            endHandlePosition = endCoordinates,
            previousHandlePosition = null,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        assertThat(selectable.getSelectionCalledTimes).isEqualTo(1)
        assertThat(selectable.lastStartHandlePosition).isEqualTo(startCoordinates)
        assertThat(selectable.lastEndHandlePosition).isEqualTo(endCoordinates)
        assertThat(selectable.lastContainerLayoutCoordinates)
            .isEqualTo(selectionManager.requireContainerCoordinates())
        assertThat(selectable.lastAdjustment).isEqualTo(SelectionAdjustment.None)
        assertThat(selectable.lastPreviousSelection).isEqualTo(fakeSelection)

        verify(selectableAnother, times(1))
            .updateSelection(
                startHandlePosition = startCoordinates,
                endHandlePosition = endCoordinates,
                previousHandlePosition = null,
                isStartHandle = false,
                containerLayoutCoordinates = selectionManager.requireContainerCoordinates(),
                adjustment = SelectionAdjustment.None,
                previousSelection = fakeSelection
            )
        verify(
            hapticFeedback,
            times(1)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    @Test
    fun updateSelection_selection_does_not_change_hapticFeedBack_Not_triggered() {
        val selection: Selection = fakeSelection
        selectable.selectionToReturn = selection

        selectionManager.updateSelection(
            startHandlePosition = startCoordinates,
            endHandlePosition = endCoordinates,
            previousHandlePosition = null,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        verify(
            hapticFeedback,
            times(0)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    @Test
    fun updateSelection_selectable_drag_startHandle() {
        selectionRegistrar.subscribe(endSelectable)
        whenever(
            endSelectable.updateSelection(
                anyOffset(), anyOffset(), anyOffset(), any(), any(), any(), any()
            )
        ).thenReturn(Pair(null, false))
        whenever(endSelectable.getLayoutCoordinates()).thenReturn(mock())
        val previousStartHandlePosition = Offset(3f, 300f)
        val newStartHandlePosition = Offset(3f, 600f)
        selectionManager.updateSelection(
            newPosition = newStartHandlePosition,
            previousPosition = previousStartHandlePosition,
            isStartHandle = true,
            adjustment = SelectionAdjustment.None
        )

        assertThat(selectable.getSelectionCalledTimes).isEqualTo(1)
        assertThat(selectable.lastStartHandlePosition).isEqualTo(newStartHandlePosition)
        assertThat(selectable.lastPreviousHandlePosition).isEqualTo(previousStartHandlePosition)
        assertThat(selectable.lastContainerLayoutCoordinates)
            .isEqualTo(selectionManager.requireContainerCoordinates())
        assertThat(selectable.lastAdjustment).isEqualTo(SelectionAdjustment.None)
        assertThat(selectable.lastPreviousSelection).isEqualTo(fakeSelection)

        verify(
            hapticFeedback,
            times(1)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    @Test
    fun updateSelection_selectable_drag_endHandle() {
        selectionRegistrar.subscribe(startSelectable)
        whenever(
            startSelectable.updateSelection(
                anyOffset(), anyOffset(), anyOffset(), any(), any(), any(), any()
            )
        ).thenReturn(Pair(null, false))
        whenever(startSelectable.getLayoutCoordinates()).thenReturn(mock())
        val previousStartHandlePosition = Offset(3f, 300f)
        val newStartHandlePosition = Offset(3f, 600f)
        selectionManager.updateSelection(
            newPosition = newStartHandlePosition,
            previousPosition = previousStartHandlePosition,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        assertThat(selectable.getSelectionCalledTimes).isEqualTo(1)
        assertThat(selectable.lastEndHandlePosition).isEqualTo(newStartHandlePosition)
        assertThat(selectable.lastPreviousHandlePosition).isEqualTo(previousStartHandlePosition)
        assertThat(selectable.lastContainerLayoutCoordinates)
            .isEqualTo(selectionManager.requireContainerCoordinates())
        assertThat(selectable.lastAdjustment).isEqualTo(SelectionAdjustment.None)
        assertThat(selectable.lastPreviousSelection).isEqualTo(fakeSelection)

        verify(
            hapticFeedback,
            times(1)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    @Test
    fun updateSelection_consumeDrag_return_true() {
        selectionRegistrar.subscribe(startSelectable)
        // The start selectable returns true and consumes the drag.
        whenever(
            startSelectable.updateSelection(
                anyOffset(), anyOffset(), anyOffset(), any(), any(), any(), any()
            )
        ).thenReturn(Pair(null, true))
        whenever(startSelectable.getLayoutCoordinates()).thenReturn(mock())

        val previousStartHandlePosition = Offset(3f, 300f)
        val newStartHandlePosition = Offset(3f, 600f)

        val consumed = selectionManager.updateSelection(
            newPosition = newStartHandlePosition,
            previousPosition = previousStartHandlePosition,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        assertThat(consumed).isTrue()
    }

    @Test
    fun updateSelection_notConsumeDrag_return_false() {
        selectionRegistrar.subscribe(startSelectable)
        // The start selectable returns false and does not consume the drag.
        whenever(
            startSelectable.updateSelection(
                anyOffset(), anyOffset(), anyOffset(), any(), any(), any(), any()
            )
        ).thenReturn(Pair(null, false))
        whenever(startSelectable.getLayoutCoordinates()).thenReturn(mock())

        // selection cannot change, else consumed will be true.
        // the updated selection is null, so set the initial selection to null as well.
        selectionManager.selection = null

        val previousStartHandlePosition = Offset(3f, 300f)
        val newStartHandlePosition = Offset(3f, 600f)

        val consumed = selectionManager.updateSelection(
            newPosition = newStartHandlePosition,
            previousPosition = previousStartHandlePosition,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        assertThat(consumed).isFalse()
    }

    @Test
    fun updateSelection_notConsumeDrag_butSelectionChange_return_true() {
        selectionRegistrar.subscribe(startSelectable)
        // The start selectable returns false and does not consume the drag.
        whenever(
            startSelectable.updateSelection(
                anyOffset(), anyOffset(), anyOffset(), any(), any(), any(), any()
            )
        ).thenReturn(Pair(null, false))
        whenever(startSelectable.getLayoutCoordinates()).thenReturn(mock())

        val previousStartHandlePosition = Offset(3f, 300f)
        val newStartHandlePosition = Offset(3f, 600f)

        // new selection is null, so it will be counted as a change
        val consumed = selectionManager.updateSelection(
            newPosition = newStartHandlePosition,
            previousPosition = previousStartHandlePosition,
            isStartHandle = false,
            adjustment = SelectionAdjustment.None
        )

        assertThat(consumed).isTrue()
    }

    @Test
    fun mergeSelections_selectAll() {
        val anotherSelectableId = 100L
        val selectableAnother = mock<Selectable>()
        whenever(selectableAnother.selectableId).thenReturn(anotherSelectableId)

        selectionRegistrar.subscribe(selectableAnother)

        selectionManager.selectAll(
            selectableId = selectableId,
            previousSelection = fakeSelection
        )

        verify(selectableAnother, times(0)).getSelectAllSelection()
        verify(
            hapticFeedback,
            times(1)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    @Test
    fun isNonEmptySelection_whenNonEmptySelection_sameLine_returnsTrue() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text)
        val startOffset = text.indexOf('e')
        val endOffset = text.indexOf('m')
        selectable.textToReturn = annotatedString
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = selectableId
            ),
            handlesCrossed = false
        )

        assertThat(selectionManager.isNonEmptySelection()).isTrue()
    }

    @Test
    fun isNonEmptySelection_whenEmptySelection_sameLine_returnsFalse() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text)
        val startOffset = text.indexOf('e')
        selectable.textToReturn = annotatedString
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            handlesCrossed = false
        )

        assertThat(selectionManager.isNonEmptySelection()).isFalse()
    }

    @Test
    fun isNonEmptySelection_whenNonEmptySelection_multiLine_returnsTrue() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('m')
        val endOffset = text.indexOf('x')

        selectionRegistrar.subscribe(endSelectable)
        selectionRegistrar.subscribe(middleSelectable)
        selectionRegistrar.subscribe(startSelectable)
        selectionRegistrar.subscribe(lastSelectable)
        selectionRegistrar.sorted = true
        whenever(startSelectable.getText()).thenReturn(annotatedString)
        whenever(middleSelectable.getText()).thenReturn(annotatedString)
        whenever(endSelectable.getText()).thenReturn(annotatedString)
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = startSelectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = endSelectableId
            ),
            handlesCrossed = true
        )

        assertThat(selectionManager.isNonEmptySelection()).isTrue()
    }

    @Test
    fun isNonEmptySelection_whenEmptySelection_multiLine_returnsFalse() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text)
        val startOffset = text.length
        val endOffset = 0

        selectionRegistrar.subscribe(startSelectable)
        selectionRegistrar.subscribe(middleSelectable)
        selectionRegistrar.subscribe(endSelectable)
        selectionRegistrar.subscribe(lastSelectable)
        selectionRegistrar.sorted = true
        whenever(startSelectable.getText()).thenReturn(annotatedString)
        whenever(middleSelectable.getText()).thenReturn(AnnotatedString(""))
        whenever(endSelectable.getText()).thenReturn(annotatedString)
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = startSelectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = endSelectableId
            ),
            handlesCrossed = false
        )

        assertThat(selectionManager.isNonEmptySelection()).isFalse()
    }

    @Test
    fun isNonEmptySelection_whenEmptySelection_multiLineCrossed_returnsFalse() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text)
        val startOffset = 0
        val endOffset = text.length

        selectionRegistrar.subscribe(endSelectable)
        selectionRegistrar.subscribe(middleSelectable)
        selectionRegistrar.subscribe(startSelectable)
        selectionRegistrar.subscribe(lastSelectable)
        selectionRegistrar.sorted = true
        whenever(startSelectable.getText()).thenReturn(annotatedString)
        whenever(middleSelectable.getText()).thenReturn(AnnotatedString(""))
        whenever(endSelectable.getText()).thenReturn(annotatedString)
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = startSelectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = endSelectableId
            ),
            handlesCrossed = true
        )

        assertThat(selectionManager.isNonEmptySelection()).isFalse()
    }

    @Test
    fun getSelectedText_selection_null_return_null() {
        selectionManager.selection = null

        assertThat(selectionManager.getSelectedText()).isNull()
        assertThat(selectable.getTextCalledTimes).isEqualTo(0)
    }

    @Test
    fun getSelectedText_not_crossed_single_widget() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('e')
        val endOffset = text.indexOf('m')
        selectable.textToReturn = annotatedString
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = selectableId
            ),
            handlesCrossed = false
        )

        assertThat(selectionManager.getSelectedText())
            .isEqualTo(annotatedString.subSequence(startOffset, endOffset))
        assertThat(selectable.getTextCalledTimes).isEqualTo(1)
    }

    @Test
    fun getSelectedText_crossed_single_widget() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('m')
        val endOffset = text.indexOf('x')
        selectable.textToReturn = annotatedString
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = selectableId
            ),
            handlesCrossed = true
        )

        assertThat(selectionManager.getSelectedText())
            .isEqualTo(annotatedString.subSequence(endOffset, startOffset))
        assertThat(selectable.getTextCalledTimes).isEqualTo(1)
    }

    @Test
    fun getSelectedText_not_crossed_multi_widgets() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('m')
        val endOffset = text.indexOf('x')

        selectionRegistrar.subscribe(startSelectable)
        selectionRegistrar.subscribe(middleSelectable)
        selectionRegistrar.subscribe(endSelectable)
        selectionRegistrar.subscribe(lastSelectable)
        selectionRegistrar.sorted = true
        whenever(startSelectable.getText()).thenReturn(annotatedString)
        whenever(middleSelectable.getText()).thenReturn(annotatedString)
        whenever(endSelectable.getText()).thenReturn(annotatedString)
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = startSelectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = endSelectableId
            ),
            handlesCrossed = false
        )

        val result = annotatedString.subSequence(startOffset, annotatedString.length) +
            annotatedString + annotatedString.subSequence(0, endOffset)
        assertThat(selectionManager.getSelectedText()).isEqualTo(result)
        assertThat(selectable.getTextCalledTimes).isEqualTo(0)
        verify(startSelectable, times(1)).getText()
        verify(middleSelectable, times(1)).getText()
        verify(endSelectable, times(1)).getText()
        verify(lastSelectable, times(0)).getText()
    }

    @Test
    fun getSelectedText_crossed_multi_widgets() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('m')
        val endOffset = text.indexOf('x')

        selectionRegistrar.subscribe(endSelectable)
        selectionRegistrar.subscribe(middleSelectable)
        selectionRegistrar.subscribe(startSelectable)
        selectionRegistrar.subscribe(lastSelectable)
        selectionRegistrar.sorted = true
        whenever(startSelectable.getText()).thenReturn(annotatedString)
        whenever(middleSelectable.getText()).thenReturn(annotatedString)
        whenever(endSelectable.getText()).thenReturn(annotatedString)
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = startSelectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = endSelectableId
            ),
            handlesCrossed = true
        )

        val result = annotatedString.subSequence(endOffset, annotatedString.length) +
            annotatedString + annotatedString.subSequence(0, startOffset)
        assertThat(selectionManager.getSelectedText()).isEqualTo(result)
        assertThat(selectable.getTextCalledTimes).isEqualTo(0)
        verify(startSelectable, times(1)).getText()
        verify(middleSelectable, times(1)).getText()
        verify(endSelectable, times(1)).getText()
        verify(lastSelectable, times(0)).getText()
    }

    @Test
    fun copy_selection_null_not_trigger_clipboardmanager() {
        selectionManager.selection = null

        selectionManager.copy()

        verify(clipboardManager, times(0)).setText(any())
    }

    @Test
    fun copy_selection_not_null_trigger_clipboardmanager_setText() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('m')
        val endOffset = text.indexOf('x')
        selectable.textToReturn = annotatedString
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = selectableId
            ),
            handlesCrossed = true
        )

        selectionManager.copy()

        verify(clipboardManager, times(1)).setText(
            annotatedString.subSequence(
                endOffset,
                startOffset
            )
        )
    }

    @Test
    fun showSelectionToolbar_trigger_textToolbar_showMenu() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('m')
        val endOffset = text.indexOf('x')
        selectable.textToReturn = annotatedString
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = selectableId
            ),
            handlesCrossed = true
        )
        selectionManager.hasFocus = true

        selectionManager.showToolbar = true

        verify(textToolbar, times(1)).showMenu(
            eq(Rect.Zero),
            any(),
            isNull(),
            isNull(),
            isNull()
        )
    }

    @Test
    fun showSelectionToolbar_withoutFocus_notTrigger_textToolbar_showMenu() {
        val text = "Text Demo"
        val annotatedString = AnnotatedString(text = text)
        val startOffset = text.indexOf('m')
        val endOffset = text.indexOf('x')
        selectable.textToReturn = annotatedString
        selectionManager.selection = Selection(
            start = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = startOffset,
                selectableId = selectableId
            ),
            end = Selection.AnchorInfo(
                direction = ResolvedTextDirection.Ltr,
                offset = endOffset,
                selectableId = selectableId
            ),
            handlesCrossed = true
        )
        selectionManager.hasFocus = false

        selectionManager.showToolbar = true

        verify(textToolbar, never()).showMenu(
            eq(Rect.Zero),
            any(),
            isNull(),
            isNull(),
            isNull()
        )
    }

    @Test
    fun onRelease_selectionMap_is_setToEmpty() {
        val fakeSelection =
            Selection(
                start = Selection.AnchorInfo(
                    direction = ResolvedTextDirection.Ltr,
                    offset = 0,
                    selectableId = startSelectableId
                ),
                end = Selection.AnchorInfo(
                    direction = ResolvedTextDirection.Ltr,
                    offset = 5,
                    selectableId = endSelectableId
                )
            )
        var selection: Selection? = fakeSelection
        val lambda: (Selection?) -> Unit = { selection = it }
        val spyLambda = spy(lambda)
        selectionManager.onSelectionChange = spyLambda
        selectionManager.selection = fakeSelection

        selectionManager.onRelease()

        verify(selectionRegistrar).subselections = emptyMap()

        assertThat(selection).isNull()
        verify(spyLambda, times(1)).invoke(null)
        verify(
            hapticFeedback,
            times(1)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    @Test
    fun notifySelectableChange_clears_selection() {
        val fakeSelection =
            Selection(
                start = Selection.AnchorInfo(
                    direction = ResolvedTextDirection.Ltr,
                    offset = 0,
                    selectableId = startSelectableId
                ),
                end = Selection.AnchorInfo(
                    direction = ResolvedTextDirection.Ltr,
                    offset = 5,
                    selectableId = startSelectableId
                )
            )
        var selection: Selection? = fakeSelection
        val lambda: (Selection?) -> Unit = { selection = it }
        val spyLambda = spy(lambda)
        selectionManager.onSelectionChange = spyLambda
        selectionManager.selection = fakeSelection

        selectionRegistrar.subselections = mapOf(
            startSelectableId to fakeSelection
        )
        selectionRegistrar.notifySelectableChange(startSelectableId)

        verify(selectionRegistrar).subselections = emptyMap()
        assertThat(selection).isNull()
        verify(spyLambda, times(1)).invoke(null)
        verify(
            hapticFeedback,
            times(1)
        ).performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

private fun anyOffset(): Offset {
    return argThat { any: Any? ->
        any == null || any is Long || any is Offset
    } as Offset? ?: Offset.Zero
}
