package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.annotation.STTM;

import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.util.ThreadExecutor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ThreadExecutor.complete() checks only the last submitted task, and only for an Exception. ESD's worker
 * tasks go through WorkerFailures so that an Error in any task fails the operator.
 */
public class TestSpectralDiversityWorkerFailures {

    @Test
    @STTM("SNAP-4387")
    public void errorInAnEarlierTaskIsRethrown() throws Exception {
        final ThreadExecutor executor = new ThreadExecutor(2);
        final SpectralDiversityOp.WorkerFailures failures = new SpectralDiversityOp.WorkerFailures();

        executor.execute(failures.wrap(() -> {
            throw new OutOfMemoryError("Java heap space");
        }));
        executor.execute(failures.wrap(() -> { }));
        executor.complete(); // the last task succeeded, so this reports nothing

        try {
            failures.throwIfAny("Range shift estimation");
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getCause() instanceof OutOfMemoryError);
            assertTrue(e.getMessage().contains("Range shift estimation failed in 1 task(s)"));
        }
    }

    @Test
    @STTM("SNAP-4387")
    public void allFailuresAreKept() throws Exception {
        final ThreadExecutor executor = new ThreadExecutor(3);
        final SpectralDiversityOp.WorkerFailures failures = new SpectralDiversityOp.WorkerFailures();
        for (int i = 0; i < 3; i++) {
            executor.execute(failures.wrap(() -> {
                throw new IllegalStateException("boom");
            }));
        }
        executor.complete();

        try {
            failures.throwIfAny("Azimuth shift estimation");
            fail("expected OperatorException");
        } catch (OperatorException e) {
            assertTrue(e.getMessage().contains("failed in 3 task(s)"));
            assertEquals(2, e.getSuppressed().length);
        }
    }

    @Test
    @STTM("SNAP-4387")
    public void noFailuresDoesNotThrow() throws Exception {
        final ThreadExecutor executor = new ThreadExecutor(2);
        final SpectralDiversityOp.WorkerFailures failures = new SpectralDiversityOp.WorkerFailures();
        executor.execute(failures.wrap(() -> { }));
        executor.complete();
        failures.throwIfAny("anything");
    }
}
