package ru.andriyshkoy.lifeagent.notes.application

import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository

class CreateNote(
    private val repository: NotesRepository,
) {
    suspend operator fun invoke(command: CreateNoteCommand): NoteMutationOutcome =
        repository.create(command)
}
