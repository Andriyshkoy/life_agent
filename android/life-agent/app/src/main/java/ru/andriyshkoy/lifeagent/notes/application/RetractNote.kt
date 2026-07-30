package ru.andriyshkoy.lifeagent.notes.application

import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand

class RetractNote(
    private val repository: NotesRepository,
) {
    suspend operator fun invoke(command: RetractNoteCommand): NoteMutationOutcome =
        repository.retract(command)
}
