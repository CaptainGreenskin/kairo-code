package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InputAccumulatorTest {

    private InputAccumulator acc;

    @BeforeEach
    void setUp() {
        acc = new InputAccumulator();
    }

    // 1. Normal line returns immediately
    @Test
    void normalLine_returnsImmediately() {
        Optional<String> result = acc.feed("hello world");
        assertThat(result).hasValue("hello world");
        assertThat(acc.isAccumulating()).isFalse();
    }

    // 2. Backslash continuation: two lines joined
    @Test
    void continuation_twoLines_joinedWithNewline() {
        assertThat(acc.feed("first \\")).isEmpty();
        assertThat(acc.isAccumulating()).isTrue();
        assertThat(acc.getMode()).isEqualTo(InputAccumulator.Mode.CONTINUATION);

        Optional<String> result = acc.feed("second");
        assertThat(result).hasValue("first \nsecond");
        assertThat(acc.isAccumulating()).isFalse();
    }

    // 3. Multiple continuation lines
    @Test
    void continuation_threeLines_allJoined() {
        assertThat(acc.feed("line1\\")).isEmpty();
        assertThat(acc.feed("line2\\")).isEmpty();
        Optional<String> result = acc.feed("line3");
        assertThat(result).hasValue("line1\nline2\nline3");
    }

    // 4. Heredoc with EOF delimiter
    @Test
    void heredoc_eof_returnsJoinedContent() {
        assertThat(acc.feed("<<EOF")).isEmpty();
        assertThat(acc.getMode()).isEqualTo(InputAccumulator.Mode.HEREDOC);
        assertThat(acc.feed("line one")).isEmpty();
        assertThat(acc.feed("line two")).isEmpty();
        Optional<String> result = acc.feed("EOF");
        assertThat(result).hasValue("line one\nline two");
        assertThat(acc.isAccumulating()).isFalse();
    }

    // 5. Custom delimiter
    @Test
    void heredoc_customDelimiter_DONE() {
        assertThat(acc.feed("<<DONE")).isEmpty();
        assertThat(acc.feed("content here")).isEmpty();
        Optional<String> result = acc.feed("DONE");
        assertThat(result).hasValue("content here");
    }

    // 6. << alone (no word) is returned as-is, no heredoc
    @Test
    void bareDoubleAngle_noHeredoc() {
        Optional<String> result = acc.feed("<<");
        assertThat(result).hasValue("<<");
        assertThat(acc.isAccumulating()).isFalse();
    }

    // 7. Empty heredoc: <<EOF then immediately EOF
    @Test
    void heredoc_empty_returnsEmptyString() {
        assertThat(acc.feed("<<EOF")).isEmpty();
        Optional<String> result = acc.feed("EOF");
        assertThat(result).hasValue("");
    }

    // 8. Heredoc preserves blank lines
    @Test
    void heredoc_preservesBlankLines() {
        assertThat(acc.feed("<<END")).isEmpty();
        assertThat(acc.feed("first")).isEmpty();
        assertThat(acc.feed("")).isEmpty();
        assertThat(acc.feed("third")).isEmpty();
        Optional<String> result = acc.feed("END");
        assertThat(result).hasValue("first\n\nthird");
    }

    // 9. Reset clears state
    @Test
    void reset_clearsAccumulationState() {
        acc.feed("<<EOF");
        acc.feed("some content");
        assertThat(acc.isAccumulating()).isTrue();

        acc.reset();
        assertThat(acc.isAccumulating()).isFalse();
        assertThat(acc.getMode()).isEqualTo(InputAccumulator.Mode.NORMAL);

        // After reset, normal input works
        Optional<String> result = acc.feed("fresh input");
        assertThat(result).hasValue("fresh input");
    }

    // 10. Interrupt during continuation — reset works
    @Test
    void interruptDuringContinuation_resetWorks() {
        acc.feed("partial\\");
        assertThat(acc.isAccumulating()).isTrue();

        acc.reset();
        assertThat(acc.isAccumulating()).isFalse();

        Optional<String> result = acc.feed("new input");
        assertThat(result).hasValue("new input");
    }

    // 11. Backslash at end of heredoc content line is NOT treated as continuation
    @Test
    void heredoc_backslashInContent_notContinuation() {
        assertThat(acc.feed("<<EOF")).isEmpty();
        assertThat(acc.feed("path/to/file\\")).isEmpty();
        assertThat(acc.getMode()).isEqualTo(InputAccumulator.Mode.HEREDOC);
        assertThat(acc.feed("next line")).isEmpty();
        Optional<String> result = acc.feed("EOF");
        assertThat(result).hasValue("path/to/file\\\nnext line");
    }

    // 12. Very large input via continuation (100+ lines)
    @Test
    void continuation_manyLines_worksCorrectly() {
        for (int i = 0; i < 100; i++) {
            assertThat(acc.feed("line" + i + "\\")).isEmpty();
        }
        Optional<String> result = acc.feed("last");
        assertThat(result).isPresent();
        String[] parts = result.get().split("\n");
        assertThat(parts).hasSize(101);
        assertThat(parts[0]).isEqualTo("line0");
        assertThat(parts[100]).isEqualTo("last");
    }

    // 13. Shell-like content in heredoc (lines starting with !) is NOT executed
    @Test
    void heredoc_shellLikeContent_notExecuted() {
        assertThat(acc.feed("<<EOF")).isEmpty();
        assertThat(acc.feed("!ls -la")).isEmpty();
        assertThat(acc.feed("!rm -rf /")).isEmpty();
        Optional<String> result = acc.feed("EOF");
        assertThat(result).hasValue("!ls -la\n!rm -rf /");
    }

    // 14. Heredoc delimiter with underscore and digits
    @Test
    void heredoc_delimiterWithUnderscoreAndDigits() {
        assertThat(acc.feed("<<MY_BLOCK_2")).isEmpty();
        assertThat(acc.feed("data")).isEmpty();
        Optional<String> result = acc.feed("MY_BLOCK_2");
        assertThat(result).hasValue("data");
    }

    // 15. Invalid heredoc delimiter (starts with digit) is returned as-is
    @Test
    void heredoc_invalidDelimiter_startsWithDigit_noHeredoc() {
        Optional<String> result = acc.feed("<<123");
        assertThat(result).hasValue("<<123");
        assertThat(acc.isAccumulating()).isFalse();
    }

    // 16. After completing one multi-line, can start another
    @Test
    void sequentialMultiLineInputs_workIndependently() {
        // First: continuation
        acc.feed("a\\");
        Optional<String> r1 = acc.feed("b");
        assertThat(r1).hasValue("a\nb");

        // Second: heredoc
        acc.feed("<<X");
        acc.feed("hello");
        Optional<String> r2 = acc.feed("X");
        assertThat(r2).hasValue("hello");

        assertThat(acc.isAccumulating()).isFalse();
    }
}
