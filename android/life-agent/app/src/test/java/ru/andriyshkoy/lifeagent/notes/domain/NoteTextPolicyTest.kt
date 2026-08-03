package ru.andriyshkoy.lifeagent.notes.domain

import org.junit.Assert.assertThrows
import org.junit.Test

class NoteTextPolicyTest {
    @Test
    fun `blank text is rejected`() {
        assertThrows(InvalidNoteTextException::class.java) {
            NoteTextPolicy.validate(" \n\t")
        }
    }

    @Test
    fun `limit counts Unicode code points rather than UTF-16 units`() {
        NoteTextPolicy.validate("😀".repeat(NoteTextPolicy.MAX_CODE_POINTS))

        assertThrows(InvalidNoteTextException::class.java) {
            NoteTextPolicy.validate("😀".repeat(NoteTextPolicy.MAX_CODE_POINTS + 1))
        }
    }

    @Test
    fun `validated text is not silently normalized`() {
        NoteTextPolicy.validate("  строка с сохранёнными краями  ")
    }
}
