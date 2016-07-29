package avalanche.core.ingestion.http;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.VisibleForTesting;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import avalanche.core.ingestion.AvalancheIngestion;
import avalanche.core.ingestion.ServiceCall;
import avalanche.core.ingestion.ServiceCallback;
import avalanche.core.ingestion.models.LogContainer;
import avalanche.core.utils.AvalancheLog;

/**
 * Decorator managing retries.
 */
public class AvalancheIngestionRetryer extends AvalancheIngestionDecorator {

    /**
     * Retry intervals to use, array index is to use the value for each retry. When we used all the array values, we give up and forward the last error.
     */
    @VisibleForTesting
    static final long[] RETRY_INTERVALS = new long[]{
            TimeUnit.SECONDS.toMillis(10),
            TimeUnit.MINUTES.toMillis(5),
            TimeUnit.MINUTES.toMillis(20)
    };

    /**
     * Android "timer" using the main thread loop.
     */
    private final Handler mHandler;

    /**
     * Random object for interval randomness.
     */
    private final Random mRandom = new Random();

    /**
     * Init with default retry policy.
     *
     * @param decoratedApi API to decorate.
     */
    public AvalancheIngestionRetryer(AvalancheIngestion decoratedApi) {
        this(decoratedApi, new Handler(Looper.getMainLooper()));
    }

    /**
     * Init.
     *
     * @param decoratedApi API to decorate.
     * @param handler      handler for timed retries.
     */
    @VisibleForTesting
    AvalancheIngestionRetryer(AvalancheIngestion decoratedApi, Handler handler) {
        super(decoratedApi);
        mHandler = handler;
    }

    @Override
    public ServiceCall sendAsync(UUID appKey, UUID installId, LogContainer logContainer, ServiceCallback serviceCallback) throws IllegalArgumentException {

        /* Wrap the call with the retry logic and call delegate. */
        RetryableCall retryableCall = new RetryableCall(mDecoratedApi, appKey, installId, logContainer, serviceCallback);
        retryableCall.run();
        return retryableCall;
    }

    /**
     * Retry wrapper logic.
     */
    private class RetryableCall extends AvalancheIngestionCallDecorator {

        /**
         * Current retry counter. 0 means its the first try.
         */
        private int mRetryCount;

        RetryableCall(AvalancheIngestion decoratedApi, UUID appKey, UUID installId, LogContainer logContainer, ServiceCallback serviceCallback) {
            super(decoratedApi, appKey, installId, logContainer, serviceCallback);
        }

        @Override
        public synchronized void cancel() {
            mHandler.removeCallbacks(this);
            super.cancel();
        }

        @Override
        public void onCallFailed(Exception e) {
            if (mRetryCount < RETRY_INTERVALS.length && HttpUtils.isRecoverableError(e)) {
                long delay = RETRY_INTERVALS[mRetryCount++] / 2;
                delay += mRandom.nextInt((int) delay);
                AvalancheLog.warn("Try #" + mRetryCount + " failed and will be retried in " + delay + " ms", e);
                mHandler.postDelayed(this, delay);
            } else
                mServiceCallback.onCallFailed(e);
        }
    }
}
