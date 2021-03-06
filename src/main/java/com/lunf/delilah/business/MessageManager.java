package com.lunf.delilah.business;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WorkerPool;
import com.lunf.delilah.domain.DisruptorPoolExceptionHandler;
import com.lunf.delilah.domain.MessageEvent;
import com.lunf.delilah.domain.PushMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;

import javax.annotation.PostConstruct;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

public class MessageManager {
    private WorkerPool<MessageEvent> workerPool;

    @Autowired
    private RingBuffer<MessageEvent> ringBuffer;

    @Value("${num_available_threads}")
    private int threadCount;

    @Autowired
    private SequenceBarrier sequenceBarrier;

    @Autowired
    private ExecutorService executorService;

    private MessageWorker[] workers;

    private BlockingQueue<PushMessage> overflowQ = new LinkedBlockingQueue<>();

    private Thread overflowQThread = new Thread(new OverflowManager());

    @Autowired
    private DisruptorPoolExceptionHandler disruptorPoolExceptionHandler;

    @Autowired
    private ApplicationContext applicationContext;

    private static final Logger logger = LoggerFactory.getLogger(MessageManager.class);

    @PostConstruct
    private void setupMessageManager() {

        this.workers = new MessageWorker[this.threadCount];

        for (int i = 0; i < this.threadCount; i++) {
            this.workers[i] = this.applicationContext.getBean(MessageWorker.class);
        }

        this.workerPool = new WorkerPool<>(this.ringBuffer, this.sequenceBarrier,
                this.disruptorPoolExceptionHandler, this.workers);

        ringBuffer.addGatingSequences(this.workerPool.getWorkerSequences());
    }

    public void startMessageManager() {

        this.overflowQThread.start();

        this.ringBuffer = this.workerPool.start(executorService);
    }

    public void process(PushMessage message) {

        try {
            if (ringBuffer.remainingCapacity() > 0) {
                publishToBuffer(message);
            } else {
                overflowQ.put(message);
            }
        } catch (Exception e) {
            logger.error("Exception processing message {}", message.getDeviceNotificationId(), e);
        }
    }

    private void publishToBuffer(PushMessage message) {

        long sequence = ringBuffer.next();

        MessageEvent messageEvent = ringBuffer.get(sequence);
        messageEvent.setPushMessage(message);

        logger.info("Publishing message to buffer of type {}", message.getText());
        ringBuffer.publish(sequence);
    }

    class OverflowManager implements Runnable {

        @Override
        public void run() {

            do {

                try {

                    //Thread will sleep until overflowQ notifies
                    PushMessage message = overflowQ.take();

                    while (!ringBuffer.hasAvailableCapacity(1)) {
                        //Busy spin until capacity frees up
                        //This WILL burn CPU cycles.
                    }

                    publishToBuffer(message);
                } catch (InterruptedException e) {
                    logger.error("InterruptedException...", e);
                }

            }
            while (true);
        }
    }

}
