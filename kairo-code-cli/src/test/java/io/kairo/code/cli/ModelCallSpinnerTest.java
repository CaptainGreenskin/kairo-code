package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class ModelCallSpinnerTest {

    @Test
    void startSetsActiveTrue() {
        StringWriter sw = new StringWriter();
        ModelCallSpinner spinner = new ModelCallSpinner(new PrintWriter(sw, true), false);

        spinner.start();

        assertThat(spinner.isActive()).isTrue();

        spinner.shutdown();
    }

    @Test
    void stopSetsActiveFalse() {
        StringWriter sw = new StringWriter();
        ModelCallSpinner spinner = new ModelCallSpinner(new PrintWriter(sw, true), false);

        spinner.start();
        spinner.stop();

        assertThat(spinner.isActive()).isFalse();

        spinner.shutdown();
    }

    @Test
    void multipleStopsDoNotThrow() {
        StringWriter sw = new StringWriter();
        ModelCallSpinner spinner = new ModelCallSpinner(new PrintWriter(sw, true), false);

        assertThatCode(() -> {
            spinner.stop();
            spinner.stop();
            spinner.stop();
        }).doesNotThrowAnyException();

        spinner.shutdown();
    }

    @Test
    void startThenStopClearsOutput() {
        StringWriter sw = new StringWriter();
        ModelCallSpinner spinner = new ModelCallSpinner(new PrintWriter(sw, true), false);

        spinner.start();
        try {
            Thread.sleep(200); // let a few frames render
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        spinner.stop();

        String output = sw.toString();
        // Should contain carriage returns and space clearing
        assertThat(output).contains("\r");
        assertThat(output).contains("⠋");

        spinner.shutdown();
    }

    @Test
    void doubleStartIsNoOp() {
        StringWriter sw = new StringWriter();
        ModelCallSpinner spinner = new ModelCallSpinner(new PrintWriter(sw, true), false);

        spinner.start();
        spinner.start();

        assertThat(spinner.isActive()).isTrue();

        spinner.shutdown();
    }
}
