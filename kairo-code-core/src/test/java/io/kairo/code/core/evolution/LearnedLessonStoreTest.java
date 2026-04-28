package io.kairo.code.core.evolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.core.evolution.LearnedLessonStore.Lesson;
import io.kairo.code.core.evolution.LearnedLessonStore.Status;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class LearnedLessonStoreTest {

    @TempDir
    Path tempDir;

    private LearnedLessonStore store() {
        return new LearnedLessonStore(tempDir.resolve("learned.json"));
    }

    @Test
    void emptyStore_returnsNoLessons() {
        assertThat(store().list()).isEmpty();
        assertThat(store().listApproved()).isEmpty();
    }

    @Test
    void save_andList() {
        LearnedLessonStore s = store();
        Lesson l = Lesson.create("bash", "avoid pipes", Status.APPROVED);
        s.save(l);

        List<Lesson> all = s.list();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).toolName()).isEqualTo("bash");
        assertThat(all.get(0).lessonText()).isEqualTo("avoid pipes");
        assertThat(all.get(0).status()).isEqualTo(Status.APPROVED);
    }

    @Test
    void listApproved_filtersNonApproved() {
        LearnedLessonStore s = store();
        s.save(Lesson.create("bash", "lesson1", Status.APPROVED));
        s.save(Lesson.create("grep", "lesson2", Status.PENDING));
        s.save(Lesson.create("read", "lesson3", Status.REJECTED));

        assertThat(s.listApproved()).hasSize(1);
        assertThat(s.listApproved().get(0).toolName()).isEqualTo("bash");
    }

    @Test
    void approve_changesStatus() {
        LearnedLessonStore s = store();
        Lesson l = Lesson.create("bash", "lesson", Status.PENDING);
        s.save(l);

        boolean ok = s.approve(l.id());

        assertThat(ok).isTrue();
        assertThat(s.list().get(0).status()).isEqualTo(Status.APPROVED);
    }

    @Test
    void reject_changesStatus() {
        LearnedLessonStore s = store();
        Lesson l = Lesson.create("bash", "lesson", Status.APPROVED);
        s.save(l);

        boolean ok = s.reject(l.id());

        assertThat(ok).isTrue();
        assertThat(s.list().get(0).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void approve_returnsFalseForUnknownId() {
        assertThat(store().approve("nonexistent")).isFalse();
    }

    @Test
    void clearRejected_removesOnlyRejected() {
        LearnedLessonStore s = store();
        s.save(Lesson.create("bash", "keep1", Status.APPROVED));
        s.save(Lesson.create("grep", "remove", Status.REJECTED));
        s.save(Lesson.create("read", "keep2", Status.PENDING));

        int removed = s.clearRejected();

        assertThat(removed).isEqualTo(1);
        assertThat(s.list()).hasSize(2);
        assertThat(s.list()).noneMatch(l -> l.status() == Status.REJECTED);
    }

    @Test
    void save_updatesExistingById() {
        LearnedLessonStore s = store();
        Lesson l = Lesson.create("bash", "original", Status.PENDING);
        s.save(l);
        s.save(l.withStatus(Status.APPROVED));

        assertThat(s.list()).hasSize(1);
        assertThat(s.list().get(0).status()).isEqualTo(Status.APPROVED);
    }
}
