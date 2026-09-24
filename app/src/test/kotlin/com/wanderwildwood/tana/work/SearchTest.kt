package com.wanderwildwood.tana.work

import com.wanderwildwood.tana.store.LocalStore
import com.wanderwildwood.tana.store.Loc
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(words: String, kind: Kind = Kind.ANY, hidden: Boolean = false): List<String> {
        val root = tmp.root
        val store = LocalStore(root)
        val found = mutableListOf<String>()
        Search({ store }).run(listOf(Loc(LocalStore.ID, "")), words, kind, hidden) { batch -> found += batch.map { it.name } }
        return found.sorted()
    }

    private fun touch(path: String) = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test fun everyWordInAnyOrderAndEitherCase() {
        touch("Books/The Hobbit - Tolkien.epub")
        touch("Books/Tolkien letters.pdf")
        touch("Music/hobbit soundtrack.mp3")
        assertEquals(listOf("The Hobbit - Tolkien.epub"), names("tolkien HOBBIT"))
        assertEquals(listOf("The Hobbit - Tolkien.epub", "hobbit soundtrack.mp3"), names("hobbit"))
    }

    @Test fun aKindAloneFindsEverythingOfThatKind() {
        touch("a/one.pdf")
        touch("b/c/two.epub")
        touch("b/three.jpg")
        assertEquals(listOf("one.pdf", "two.epub"), names("", Kind.DOCUMENTS))
    }

    @Test fun hiddenFoldersAreLeftOutUnlessAskedFor() {
        touch(".thumbnails/cover.jpg")
        touch("Pictures/cover.jpg")
        assertEquals(listOf("cover.jpg"), names("cover"))
        assertEquals(listOf("cover.jpg", "cover.jpg"), names("cover", hidden = true))
    }

    @Test fun foldersAreFoundByNameToo() {
        touch("Audiobooks/Pullman/ch1.mp3")
        assertEquals(listOf("Pullman"), names("pullman"))
    }

    @Test fun nothingTypedAndNoKindSearchesNothing() {
        touch("a.txt")
        assertEquals(emptyList<String>(), names("  "))
    }
}
