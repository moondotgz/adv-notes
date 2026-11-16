package com.advnotes.app.render

import com.advnotes.app.AdvNotesApp
import com.advnotes.app.data.AppJson
import com.advnotes.app.editor.EditorViewModel
import com.advnotes.app.model.GROUP_COLORS
import com.advnotes.app.model.Notebook
import com.advnotes.app.model.Page
import com.advnotes.app.model.PaperColor
import com.advnotes.app.model.TRASH_MILLIS
import com.advnotes.app.ui.trashDaysLeft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryFeaturesTest {
    private val repo get() = AdvNotesApp.instance.repo

    private fun summary(id: String) = repo.summaries.value.firstOrNull { it.id == id }

    @Test fun trashAndRestore() = runBlocking {
        val nb = repo.save(Notebook(title = "to trash"))
        repo.trash(nb.id)
        assertNotNull(summary(nb.id)!!.trashedAt)
        assertNotNull(repo.load(nb.id)!!.trashedAt)
        repo.restore(nb.id)
        assertNull(summary(nb.id)!!.trashedAt)
        assertNull(repo.load(nb.id)!!.trashedAt)
    }

    @Test fun purgeOnlyRemovesWhatIsOlderThanThirtyDays() = runBlocking {
        val now = 1_800_000_000_000L
        val old = repo.save(Notebook(title = "old", trashedAt = now - TRASH_MILLIS - 60_000))
        val recent = repo.save(Notebook(title = "recent", trashedAt = now - TRASH_MILLIS + 3_600_000))
        val live = repo.save(Notebook(title = "live"))
        assertEquals(1, repo.purgeTrash(now))
        assertNull(summary(old.id))
        assertNotNull(summary(recent.id))
        assertNotNull(summary(live.id))
        assertNull(repo.load(old.id))
    }

    @Test fun emptyTrashKeepsLiveNotebooks() = runBlocking {
        val a = repo.save(Notebook(title = "a", trashedAt = 5L))
        val live = repo.save(Notebook(title = "live"))
        repo.emptyTrash()
        assertNull(summary(a.id))
        assertNotNull(summary(live.id))
    }

    @Test fun daysLeftCountsDownFromThirty() {
        val t = 1_000_000_000_000L
        assertEquals(30, trashDaysLeft(t, now = t))
        assertEquals(30, trashDaysLeft(t, now = t + 1_000))
        assertEquals(29, trashDaysLeft(t, now = t + 86_400_000L + 1_000))
        assertEquals(1, trashDaysLeft(t, now = t + TRASH_MILLIS - 3_600_000))
        assertEquals(0, trashDaysLeft(t, now = t + TRASH_MILLIS + 1))
    }

    @Test fun oldNotebookFilesWithoutTheNewFieldsStillLoad() {
        val old = """{"id":"x","title":"Old","paper":"GRID","paperColor":"MINT","cover":-1,"createdAt":1,"updatedAt":2,"pages":[{"id":"p1"}]}"""
        val nb = AppJson.decodeFromString(Notebook.serializer(), old)
        assertNull(nb.trashedAt)
        assertTrue(nb.groups.isEmpty() && nb.bookmarks.isEmpty())
        assertFalse(nb.lockPages)
    }

    @Test fun pinkExistsAndSurvivesSaving() = runBlocking {
        assertTrue(PaperColor.entries.any { it.name == "PINK" && !it.dark })
        val nb = repo.save(Notebook(title = "pink", paperColor = PaperColor.PINK))
        assertEquals(PaperColor.PINK, repo.load(nb.id)!!.paperColor)
    }

    private fun vmFor(nb: Notebook): EditorViewModel {
        val vm = EditorViewModel(AdvNotesApp.instance)
        vm.open(nb.id)
        repeat(100) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (vm.ui.value.notebook != null) return vm
            Thread.sleep(30)
        }
        return vm
    }

    @Test fun bookmarkGroups() {
        val nb = runBlocking { repo.save(Notebook(title = "groups", pages = listOf(Page(), Page(), Page()))) }
        val vm = vmFor(nb)
        val pages = vm.ui.value.notebook!!.pages

        assertNull(vm.addGroup("   "))                         // blank names are ignored
        val exam = vm.addGroup("Exam")!!
        val review = vm.addGroup("Review")!!
        assertEquals(2, vm.ui.value.notebook!!.groups.size)
        assertTrue(vm.ui.value.notebook!!.groups.map { it.color }.all { it in GROUP_COLORS })

        vm.toggleBookmark(pages[0].id, exam)
        vm.toggleBookmark(pages[1].id, exam)
        vm.toggleBookmark(pages[1].id, review)
        assertEquals(3, vm.ui.value.notebook!!.bookmarks.size)
        vm.toggleBookmark(pages[1].id, review)                 // toggling again removes it
        assertEquals(2, vm.ui.value.notebook!!.bookmarks.size)

        vm.renameGroup(exam, "Finals")
        assertEquals("Finals", vm.ui.value.notebook!!.groups.first { it.id == exam }.name)

        vm.goTo(0); vm.deletePage()                            // deleting a page drops its bookmarks
        assertTrue(vm.ui.value.notebook!!.bookmarks.none { it.pageId == pages[0].id })
        assertEquals(1, vm.ui.value.notebook!!.bookmarks.size)

        vm.deleteGroup(exam)                                   // deleting a group drops its bookmarks
        assertTrue(vm.ui.value.notebook!!.bookmarks.isEmpty())
        assertEquals(1, vm.ui.value.notebook!!.groups.size)
    }
}
