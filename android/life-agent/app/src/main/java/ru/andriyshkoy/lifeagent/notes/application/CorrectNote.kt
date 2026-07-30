package ru.andriyshkoy.lifeagent.notes.application

import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository

class CorrectNote(
    private val repository: NotesRepository,
) {
    suspend operator fun invoke(command: CorrectNoteCommand): NoteMutationOutcome =
        repository.correct(command)
}
