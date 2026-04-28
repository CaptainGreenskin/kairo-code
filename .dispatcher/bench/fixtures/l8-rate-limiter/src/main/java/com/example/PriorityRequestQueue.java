package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Priority request queue implemented as a MAX-HEAP.
 * Higher priority (HIGH > MEDIUM > LOW) should be dequeued first.
 *
 * BUG 3: sift-up comparison direction is inverted — LOW ends up at the root
 *   instead of HIGH, turning this into a MIN-HEAP.
 */
public class PriorityRequestQueue {

    private final List<Request> heap = new ArrayList<>();

    public synchronized void enqueue(Request request) {
        heap.add(request);
        siftUp(heap.size() - 1);
    }

    public synchronized Request dequeue() {
        if (heap.isEmpty()) {
            throw new NoSuchElementException("Queue is empty");
        }
        Request top = heap.get(0);
        Request last = heap.removeLast();
        if (!heap.isEmpty()) {
            heap.set(0, last);
            siftDown(0);
        }
        return top;
    }

    public synchronized Request peek() {
        if (heap.isEmpty()) {
            return null;
        }
        return heap.get(0);
    }

    public synchronized int size() {
        return heap.size();
    }

    public synchronized boolean isEmpty() {
        return heap.isEmpty();
    }

    /**
     * Restore heap property by moving the element at index i upward.
     *
     * Bug 3: comparison is inverted — uses > instead of <, so the
     * smallest ordinal (LOW) bubbles to the root instead of the largest (HIGH).
     * Priority enum: LOW=0, MEDIUM=1, HIGH=2 — we want HIGH at the root (MAX-HEAP).
     */
    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            // Bug: should be < to make HIGH (ordinal 2) rise to the top
            if (heap.get(parent).getPriority().ordinal() > heap.get(i).getPriority().ordinal()) {
                swap(parent, i);
                i = parent;
            } else {
                break;
            }
        }
    }

    private void siftDown(int i) {
        int n = heap.size();
        while (true) {
            int largest = i;
            int left = 2 * i + 1;
            int right = 2 * i + 2;

            if (left < n && heap.get(left).getPriority().ordinal() > heap.get(largest).getPriority().ordinal()) {
                largest = left;
            }
            if (right < n && heap.get(right).getPriority().ordinal() > heap.get(largest).getPriority().ordinal()) {
                largest = right;
            }
            if (largest != i) {
                swap(i, largest);
                i = largest;
            } else {
                break;
            }
        }
    }

    private void swap(int i, int j) {
        Request tmp = heap.get(i);
        heap.set(i, heap.get(j));
        heap.set(j, tmp);
    }

    List<Request> snapshot() {
        return List.copyOf(heap);
    }
}
