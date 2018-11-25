/**
 * Copyright 2018 Oath Inc.
 * Licensed under the terms of the Apache 2.0 license.
 * Please see LICENSE file in the project root for terms.
 */

package com.oath.oak;

import javafx.util.Pair;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.AbstractMap;

class InternalOakMap<K, V> {

    /*-------------- Members --------------*/

    Logger log = Logger.getLogger(InternalOakMap.class.getName());
    final ConcurrentSkipListMap<Object, Chunk<K, V>> skiplist;    // skiplist of chunks for fast navigation
    private final AtomicReference<Chunk<K, V>> head;
    private final ByteBuffer minKey;
    private final Comparator comparator;
    private final MemoryManager memoryManager;
    private final AtomicInteger size;
    private final OakSerializer<K> keySerializer;
    private final OakSerializer<V> valueSerializer;
    private final ThreadIndexCalculator threadIndexCalculator;
    // The reference count is used to count the upper objects wrapping this internal map:
    // OakMaps (including subMaps and Views) when all of the above are closed,
    // his map can be closed and memory released.
    private final AtomicInteger referenceCount = new AtomicInteger(1);
    /*-------------- Constructors --------------*/

    /**
     * init with capacity = 2g
     */

    InternalOakMap(
            K minKey,
            OakSerializer<K> keySerializer,
            OakSerializer<V> valueSerializer,
            Comparator comparator,
            MemoryManager memoryManager,
            int chunkMaxItems,
            int chunkBytesPerItem,
            ThreadIndexCalculator threadIndexCalculator) {

        this.size = new AtomicInteger(0);
        this.memoryManager = memoryManager;

        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;

        this.comparator = comparator;

        this.minKey = ByteBuffer.allocate(this.keySerializer.calculateSize(minKey));
        this.minKey.position(0);
        this.keySerializer.serialize(minKey, this.minKey);

        this.skiplist = new ConcurrentSkipListMap<>(this.comparator);

        Chunk<K, V> head = new Chunk<K, V>(this.minKey, null, this.comparator, memoryManager, chunkMaxItems,
                chunkBytesPerItem, this.size, keySerializer, valueSerializer, threadIndexCalculator);
        this.skiplist.put(head.minKey, head);    // add first chunk (head) into skiplist
        this.head = new AtomicReference<>(head);
        this.threadIndexCalculator = threadIndexCalculator;
    }

    /*-------------- Closable --------------*/

    /**
     * cleans off heap memory
     */
    void close() {
        int res = referenceCount.decrementAndGet();
        // once reference count is zeroed, the map meant to be deleted and should not be used.
        // reference count will never grow again
        if (res == 0) {
            memoryManager.close();
        }
    }

    // yet another object started to refer to this internal map
    void open() {
        while(true) {
            int res = referenceCount.get();
            // once reference count is zeroed, the map meant to be deleted and should not be used.
            // reference count should never grow again and the referral is not allowed
            if (res == 0) {
                throw new ConcurrentModificationException();
            }
            // although it is costly CAS is used here on purpose so we never increase
            // zeroed reference count
            if (referenceCount.compareAndSet(res, res + 1)) {
                break;
            }
        }
    }

    /*-------------- size --------------*/

    /**
     * @return current off heap memory usage in bytes
     */
    long memorySize() {
        return memoryManager.allocated();
    }

    int entries() { return size.get(); }

    /*-------------- Methods --------------*/

    /**
     * finds and returns the chunk where key should be located, starting from given chunk
     */
    private Chunk<K, V> iterateChunks(Chunk<K, V> c, Object key) {
        // find chunk following given chunk (next)
        Chunk<K, V> next = c.next.getReference();

        // since skiplist isn't updated atomically in split/compaction, our key might belong in the next chunk
        // we need to iterate the chunks until we find the correct one
        while ((next != null) && (comparator.compare(next.minKey, key) <= 0)) {
            c = next;
            next = c.next.getReference();
        }

        return c;
    }

    private Rebalancer.RebalanceResult rebalance(Chunk<K, V> c, K key, V value, Consumer<ByteBuffer> computer, Operation op) {
        if (c == null) {
            assert op == Operation.NO_OP;
            return null;
        }
        Rebalancer rebalancer = new Rebalancer(c, comparator, true, memoryManager, keySerializer,
                valueSerializer, threadIndexCalculator);

        rebalancer = rebalancer.engageChunks(); // maybe we encountered a different rebalancer

        // freeze all the engaged range.
        // When completed, all update (put, next pointer update) operations on the engaged range
        // will be redirected to help the rebalance procedure
        rebalancer.freeze();

        Rebalancer.RebalanceResult result = rebalancer.createNewChunks(key, value, computer, op); // split or compact
        // if returned true then this thread was responsible for the creation of the new chunks
        // and it inserted the put

        // lists may be generated by another thread
        List<Chunk<K, V>> newChunks = rebalancer.getNewChunks();
        List<Chunk<K, V>> engaged = rebalancer.getEngagedChunks();

        connectToChunkList(engaged, newChunks);

        updateIndexAndNormalize(engaged, newChunks);


        // Go over all iterators siged in this chunk and set there lastKey. This is done so that we can free this chunks
        // buffers and the iterator will not touch released bytebuffers.
        for (Chunk chunk : engaged) {
            ConcurrentSkipListSet<Iter> viewingIterators = chunk.getSignedIterators();
            chunk.release();
            viewingIterators.forEach(iterator -> {

                int index = iterator.getNextKeyIndex();
                // Check if iterator moved to next chunk.
                // index can be 0 if iterator is starting or ending. If starting we dont set the lastKey but the
                // iterator will see RELEASED and set it itself
                if (iterator.nextChunk == chunk && index != 0) {
                    K lastKey = keySerializer.deserialize(chunk.readKey(index));
                    iterator.setLastKey(chunk, lastKey);
                }
            });
        }
        if (result.success && result.putIfAbsent) {
            size.incrementAndGet();
        }

        return result;
    }

    private void checkRebalance(Chunk c) {
        if (c.shouldRebalance()) {
            rebalance(c, null, null, null, Operation.NO_OP);
        }
    }

    private void connectToChunkList(List<Chunk<K, V>> engaged, List<Chunk<K, V>> children) {

        updateLastChild(engaged, children);
        int countIterations = 0;
        Chunk<K, V> firstEngaged = engaged.get(0);

        // replace in linked list - we now need to find previous chunk to our chunk
        // and CAS its next to point to c1, which is the same c1 for all threads who reached this point
        // since prev might be marked (in compact itself) - we need to repeat this until successful
        while (true) {
            countIterations++;
            assert (countIterations<10000); // this loop is not supposed to be infinite

            // start with first chunk (i.e., head)
            Map.Entry<Object, Chunk<K, V>> lowerEntry = skiplist.lowerEntry(firstEngaged.minKey);

            Chunk<K, V> prev = lowerEntry != null ? lowerEntry.getValue() : null;
            Chunk<K, V> curr = (prev != null) ? prev.next.getReference() : null;

            // if didn't succeed to find prev through the skiplist - start from the head
            if (prev == null || curr != firstEngaged) {
                prev = null;
                curr = skiplist.firstEntry().getValue();    // TODO we can store&update head for a little efficiency
                // iterate until found chunk or reached end of list
                while ((curr != firstEngaged) && (curr != null)) {
                    prev = curr;
                    curr = curr.next.getReference();
                }
            }

            // chunk is head, we need to "add it to the list" for linearization point
            if (curr == firstEngaged && prev == null) {
                this.head.compareAndSet(firstEngaged, children.get(0));
                break;
            }
            // chunk is not in list (someone else already updated list), so we're done with this part
            if ((curr == null) || (prev == null)) {
                //TODO Never reached
                break;
            }

            // if prev chunk is marked - it is deleted, need to help split it and then continue
            if (prev.next.isMarked()) {
                rebalance(prev, null, null, null, Operation.NO_OP);
                continue;
            }

            // try to CAS prev chunk's next - from chunk (that we split) into c1
            // c1 is the old chunk's replacement, and is already connected to c2
            // c2 is already connected to old chunk's next - so all we need to do is this replacement
            if ((prev.next.compareAndSet(firstEngaged, children.get(0), false, false)) ||
                    (!prev.next.isMarked())) {
                // if we're successful, or we failed but prev is not marked - so it means someone else was successful
                // then we're done with loop
                break;
            }
        }

    }

    private void updateLastChild(List<Chunk<K, V>> engaged, List<Chunk<K, V>> children) {
        Chunk<K, V> lastEngaged = engaged.get(engaged.size() - 1);
        Chunk<K, V> nextToLast = lastEngaged.markAndGetNext(); // also marks last engaged chunk as deleted
        Chunk<K, V> lastChild = children.get(children.size() - 1);

        lastChild.next.compareAndSet(null, nextToLast, false, false);
    }

    private void updateIndexAndNormalize(List<Chunk<K, V>> engagedChunks, List<Chunk<K, V>> children) {
        Iterator<Chunk<K, V>> iterEngaged = engagedChunks.iterator();
        Iterator<Chunk<K, V>> iterChildren = children.iterator();

        Chunk firstEngaged = iterEngaged.next();
        Chunk firstChild = iterChildren.next();

        // need to make the new chunks available, before removing old chunks
        skiplist.replace(firstEngaged.minKey, firstEngaged, firstChild);

        // remove all old chunks from index.
        while (iterEngaged.hasNext()) {
            Chunk engagedToRemove = iterEngaged.next();
            skiplist.remove(engagedToRemove.minKey, engagedToRemove); // conditional remove is used
        }

        // now after removing old chunks we can start normalizing
        firstChild.normalize();

        // for simplicity -  naive lock implementation
        // can be implemented without locks using versions on next pointer in skiplist
        while (iterChildren.hasNext()) {
            Chunk childToAdd;
            synchronized (childToAdd = iterChildren.next()) {
                if (childToAdd.state() == Chunk.State.INFANT) { // make sure it wasn't add before
                    skiplist.putIfAbsent(childToAdd.minKey, childToAdd);
                    childToAdd.normalize();
                }
                // has a built in fence, so no need to add one here
            }
        }
    }

    private boolean rebalancePutIfAbsent(Chunk<K, V> c, K key, V value) {
        Rebalancer.RebalanceResult result = rebalance(c, key, value, null, Operation.PUT_IF_ABSENT);
        assert result != null;
        if (!result.success) { // rebalance helped put
            return putIfAbsent(key, value);
        }
        return result.putIfAbsent;
    }

    private void rebalancePut(Chunk<K, V> c, K key, V value) {
        Rebalancer.RebalanceResult result = rebalance(c, key, value, null, Operation.PUT);
        assert result != null;
        if (result.success) { // rebalance helped put
            return;
        }
        put(key, value);
    }

    private void rebalanceCompute(Chunk<K, V> c, K key, V value, Consumer<ByteBuffer> computer) {
        Rebalancer.RebalanceResult result = rebalance(c, key, value, computer, Operation.COMPUTE);
        assert result != null;
        if (result.success) { // rebalance helped compute
            return;
        }
        putIfAbsentComputeIfPresent(key, value, computer);
    }

    private boolean rebalanceRemove(Chunk<K, V> c, K key) {
        Rebalancer.RebalanceResult result = rebalance(c, key, null, null, Operation.REMOVE);
        assert result != null;
        return result.success;
    }

    private boolean finishAfterPublishing(Chunk.OpData opData, Chunk<K, V> c){
        // set pointer to value
        boolean ret = c.pointToValue(opData);
        c.unpublish(opData);
        checkRebalance(c);
        return ret;
    }

    /*-------------- OakMap Methods --------------*/

    void put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }

        Chunk<K, V> c = findChunk(key); // find chunk matching key
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp != null && lookUp.handle != null) {
            lookUp.handle.put(value, valueSerializer, memoryManager);
            return;
        }

        // if chunk is frozen or infant, we can't add to it
        // we need to help rebalancer first, then proceed
        Chunk.State state = c.state();
        if (state == Chunk.State.INFANT) {
            // the infant is already connected so rebalancer won't add this put
            rebalance(c.creator(), null, null, null, Operation.NO_OP);
            put(key, value);
            return;
        }
        if (state == Chunk.State.FROZEN || state == Chunk.State.RELEASED) {
            rebalancePut(c, key, value);
            return;
        }

        int ei = -1;
        int prevHi = -1;
        if (lookUp != null) {
            ei = lookUp.entryIndex;
            assert ei > 0;
            prevHi = lookUp.handleIndex;
        }

        if (ei == -1) {
            ei = c.allocateEntryAndKey(key);
            if (ei == -1) {
                rebalancePut(c, key, value);
                return;
            }
            int prevEi = c.linkEntry(ei, true, key);
            if (prevEi != ei) {
                ei = prevEi;
                prevHi = c.getHandleIndex(prevEi);
            }
        }

        int hi = c.allocateHandle();
        if (hi == -1) {
            rebalancePut(c, key, value);
            return;
        }

        c.writeValue(hi, value); // write value in place

        Chunk.OpData opData = new Chunk.OpData(Operation.PUT, ei, hi, prevHi, null);

        // publish put
        if (!c.publish(opData)) {
            rebalancePut(c, key, value);
            return;
        }

        finishAfterPublishing(opData, c);
    }

    boolean putIfAbsent(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }

        Chunk c = findChunk(key); // find chunk matching key
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp != null && lookUp.handle != null) {
            return false;
        }

        // if chunk is frozen or infant, we can't add to it
        // we need to help rebalancer first, then proceed
        Chunk.State state = c.state();
        if (state == Chunk.State.INFANT) {
            // the infant is already connected so rebalancer won't add this put
            rebalance(c.creator(), null, null, null, Operation.NO_OP);
            return putIfAbsent(key, value);
        }
        if (state == Chunk.State.FROZEN || state == Chunk.State.RELEASED) {
            return rebalancePutIfAbsent(c, key, value);
        }


        int ei = -1;
        int prevHi = -1;
        if (lookUp != null) {
            assert lookUp.handle == null;
            ei = lookUp.entryIndex;
            assert ei > 0;
            prevHi = lookUp.handleIndex;
        }

        if (ei == -1) {
            ei = c.allocateEntryAndKey(key);
            if (ei == -1) {
                return rebalancePutIfAbsent(c, key, value);
            }
            int prevEi = c.linkEntry(ei, true, key);
            if (prevEi != ei) {
                Handle handle = c.getHandle(prevEi);
                if (handle == null) {
                    ei = prevEi;
                    prevHi = c.getHandleIndex(prevEi);
                } else {
                    return false;
                }
            }
        }

        int hi = c.allocateHandle();
        if (hi == -1) {
            return rebalancePutIfAbsent(c, key, value);
        }

        c.writeValue(hi, value); // write value in place

        Chunk.OpData opData = new Chunk.OpData(Operation.PUT_IF_ABSENT, ei, hi, prevHi, null);

        // publish put
        if (!c.publish(opData)) {
            return rebalancePutIfAbsent(c, key, value);
        }

        return finishAfterPublishing(opData, c);
    }

    void putIfAbsentComputeIfPresent(K key, V value, Consumer<ByteBuffer> computer) {
        if (key == null || value == null || computer == null) {
            throw new NullPointerException();
        }

        Chunk c = findChunk(key); // find chunk matching key
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp != null && lookUp.handle != null) {
            if (lookUp.handle.compute(computer, memoryManager)) {
                // compute was successful and handle wasn't found deleted; in case
                // this handle was already found as deleted, continue to construct another handle
                return;
            }
        }

        // if chunk is frozen or infant, we can't add to it
        // we need to help rebalancer first, then proceed
        Chunk.State state = c.state();
        if (state == Chunk.State.INFANT) {
            // the infant is already connected so rebalancer won't add this put
            rebalance(c.creator(), null, null, null, Operation.NO_OP);
            putIfAbsentComputeIfPresent(key, value, computer);
            return;
        }
        if (state == Chunk.State.FROZEN || state == Chunk.State.RELEASED) {
            rebalanceCompute(c, key, value, computer);
            return;
        }

        // we come here when no key was found, which can be in 3 cases:
        // 1. no entry in the linked list at all
        // 2. entry in the linked list, but handle is not attached
        // 3. entry in the linked list, handle attached, but handle is marked deleted
        int ei = -1;
        int prevHi = -1;
        if (lookUp != null) {
            ei = lookUp.entryIndex;
            assert ei > 0;
            prevHi = lookUp.handleIndex;
        }

        if (ei == -1) {
            ei = c.allocateEntryAndKey(key);
            if (ei == -1) {
                rebalanceCompute(c, key, value, computer);
                return;
            }
            int prevEi = c.linkEntry(ei, true, key);
            if (prevEi != ei) {
                Handle handle = c.getHandle(prevEi);
                if (handle == null) {
                    ei = prevEi;
                    prevHi = c.getHandleIndex(prevEi);
                } else {
                    if (handle.compute(computer, memoryManager)) {
                        // compute was successful and handle wasn't found deleted; in case
                        // this handle was already found as deleted, continue to construct another handle
                        return;
                    }
                }
            }
        }

        int hi = c.allocateHandle();
        if (hi == -1) {
            rebalanceCompute(c, key, value, computer);
            return;
        }

        c.writeValue(hi, value); // write value in place

        Chunk.OpData opData = new Chunk.OpData(Operation.COMPUTE, ei, hi, prevHi, computer);

        // publish put
        if (!c.publish(opData)) {
            rebalanceCompute(c, key, value, computer);
            return;
        }

        finishAfterPublishing(opData, c);
    }

    void remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }

        boolean logical = true; // when logical is false, means we have marked the handle as deleted
        Handle prev = null;

        while (true) {

            Chunk c = findChunk(key); // find chunk matching key
            Chunk.LookUp lookUp = c.lookUp(key);
            if (lookUp != null && logical) {
                prev = lookUp.handle; // remember previous handle
            }
            if (!logical && lookUp != null && prev != lookUp.handle) {
                return;  // someone else used this entry
            }

            if (lookUp == null || lookUp.handle == null) {
                return; // there is no such key
            }

            if (logical) {
                if (!lookUp.handle.remove(memoryManager)) {
                    // we didn't succeed to remove the handle was marked as deleted already
                    return;
                }
                // we have marked this handle as deleted
            }

            // if chunk is frozen or infant, we can't update it (remove deleted key, set handle index to -1)
            // we need to help rebalancer first, then proceed
            Chunk.State state = c.state();
            if (state == Chunk.State.INFANT) {
                // the infant is already connected so rebalancer won't add this put
                rebalance(c.creator(), null, null, null, Operation.NO_OP);
                logical = false;
                continue;
            }
            if (state == Chunk.State.FROZEN || state == Chunk.State.RELEASED) {
                if (!rebalanceRemove(c, key)) {
                    logical = false;
                    continue;
                }
                return;
            }


            assert lookUp.entryIndex > 0;
            assert lookUp.handleIndex > 0;
            Chunk.OpData opData = new Chunk.OpData(Operation.REMOVE, lookUp.entryIndex, -1, lookUp.handleIndex, null);

            // publish
            if (!c.publish(opData)) {
                if (!rebalanceRemove(c, key)) {
                    logical = false;
                    continue;
                }
                return;
            }

            finishAfterPublishing(opData, c);
        }
    }

    OakRBuffer get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }

        Chunk<K, V> c = findChunk(key); // find chunk matching key
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp == null || lookUp.handle == null) {
            return null;
        }
        return new OakRValueBufferImpl(lookUp.handle);
    }

    <T> T getValueTransformation(K key, Function<ByteBuffer,T> transformer) {
        if (key == null || transformer == null) {
            throw new NullPointerException();
        }

        Chunk<K, V> c = findChunk(key); // find chunk matching key
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp == null || lookUp.handle == null) {
            return null;
        }

        lookUp.handle.readLock.lock();
        T transformation = transformer.apply(lookUp.handle.getImmutableByteBuffer());
        lookUp.handle.readLock.unlock();
        return transformation;
    }

    <T> T getKeyTransformation(K key, Function<ByteBuffer,T> transformer) {
        if (key == null || transformer == null) {
            throw new NullPointerException();
        }

        Chunk<K, V> c = findChunk(key); // find chunk matching key
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp == null || lookUp.handle == null || lookUp.entryIndex == -1) {
            return null;
        }
        ByteBuffer serializedKey = c.readKey(lookUp.entryIndex);
        return transformer.apply(serializedKey);
    }

    OakRBuffer getKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }

        Chunk<K, V> c = findChunk(key);
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp == null || lookUp.handle == null || lookUp.entryIndex == -1) {
            return null;
        }
        ByteBuffer serializedKey = c.readKey(lookUp.entryIndex);
        return new OakRKeyBufferImpl(serializedKey);
    }

    OakRBuffer getMinKey() {
        Chunk<K, V> c = skiplist.firstEntry().getValue();
        ByteBuffer serializedMinKey = c.readMinKey();
        return new OakRKeyBufferImpl(serializedMinKey);
    }

    <T> T getMinKeyTransformation(Function<ByteBuffer,T> transformer) {
        if (transformer == null) {
            throw new NullPointerException();
        }

        Chunk<K, V> c = skiplist.firstEntry().getValue();
        ByteBuffer serializedMinKey = c.readMinKey();

        return transformer.apply(serializedMinKey);
    }

    OakRBuffer getMaxKey() {
        Chunk<K, V> c = skiplist.lastEntry().getValue();
        Chunk<K, V> next = c.next.getReference();
        // since skiplist isn't updated atomically in split/compaction, the max key might belong in the next chunk
        // we need to iterate the chunks until we find the last one
        while (next != null) {
            c = next;
            next = c.next.getReference();
        }

        ByteBuffer serializedMaxKey = c.readMaxKey();
        return new OakRKeyBufferImpl(serializedMaxKey);
    }

    <T> T getMaxKeyTransformation(Function<ByteBuffer,T> transformer) {
        if (transformer == null) {
            throw new NullPointerException();
        }

        Chunk<K, V> c = skiplist.lastEntry().getValue();
        Chunk<K, V> next = c.next.getReference();
        // since skiplist isn't updated atomically in split/compaction, the max key might belong in the next chunk
        // we need to iterate the chunks until we find the last one
        while (next != null) {
            c = next;
            next = c.next.getReference();
        }
        ByteBuffer serializedMaxKey = c.readMaxKey();
        return transformer.apply(serializedMaxKey);
    }

    boolean computeIfPresent(K key, Consumer<OakWBuffer> computer) {
        if (key == null || computer == null) {
            throw new NullPointerException();
        }

        Chunk c = findChunk(key); // find chunk matching key
        Chunk.LookUp lookUp = c.lookUp(key);
        if (lookUp == null || lookUp.handle == null) return false;

        lookUp.handle.compute(computer, memoryManager);
        return true;
    }

    // encapsulates finding of the chunk in the skip list and later chunk list traversal
    private Chunk findChunk(Object key) {
        Chunk c = skiplist.floorEntry(key).getValue();
        c = iterateChunks(c, key);
        return c;
    }

    /*-------------- Iterators --------------*/

    /**
     * Base of iterator classes:
     */
    abstract class Iter<T> implements OakIterator<T> {

        private K lo;
        /**
         * upper bound key, or null if to end
         */
        private K hi;
        /**
         * inclusion flag for lo
         */
        private boolean loInclusive;
        /**
         * inclusion flag for hi
         */
        private boolean hiInclusive;
        /**
         * direction
         */
        private final boolean isDescending;

        /**
         * the next node to return from next();
         */
        protected Chunk<K, V> nextChunk;
        protected Chunk.ChunkIter nextChunkIter;
        protected int nextIndex;
        /**
         * Cache of next value field to maintain weak consistency
         */
        protected Handle nextHandle;

        // Set by rebalancer if chunk is deleted
        // This is the next key to return when next() is called
        private AtomicReference<Object> lastKeyBeforeChunkDelete;

        /**
         * Initializes ascending iterator for entire range.
         */
        Iter(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending) {
            if (lo != null && hi != null &&
                    comparator.compare(lo, hi) > 0)
                throw new IllegalArgumentException("inconsistent range");

            this.lo = lo;
            this.loInclusive = loInclusive;
            this.hi = hi;
            this.hiInclusive = hiInclusive;
            this.isDescending = isDescending;
            // Upon iterator construction we attach the thread to the memory manager. With each next()
            // we use another nested attach-detach invocation to allow releasing the memory where
            // iterator already traversed. Finally to mark the thread idle we need the detach to be
            // invoked from the close of this closeable iterator.
            nextIndex = Chunk.NONE;
            nextHandle = null;
            memoryManager.startOperation();
            initChunk();
            memoryManager.stopOperation();
        }

        boolean tooLow(Object key) {
            int c;
            return (lo != null && ((c = comparator.compare(key, lo)) < 0 ||
                    (c == 0 && !loInclusive)));
        }

        boolean tooHigh(Object key) {
            int c;
            return (hi != null && ((c = comparator.compare(key, hi)) > 0 ||
                    (c == 0 && !hiInclusive)));
        }


        boolean inBounds(Object key) {
            if (!isDescending) {
                return !tooHigh(key);
            } else {
                return !tooLow(key);
            }
        }

        public final boolean hasNext() {
            return nextIndex != Chunk.NONE;
        }

        public T next() {
            try {
                memoryManager.startOperation();
                return internalNext();
            } finally {
                memoryManager.stopOperation();
            }
        }

        public int getNextKeyIndex() {
            return nextIndex;
        }

        // the actual next()
        abstract T internalNext();

        /**
         * Advances next to higher entry.
         * Return previous index
         */
        Pair<ByteBuffer, Handle> advance() {

            Object lastKey = lastKeyBeforeChunkDelete.get();
            if (!(lastKey instanceof Chunk)) {
                //Current chunk got released
                if (isDescending) {
                    hiInclusive = true;
                    hi = (K)lastKeyBeforeChunkDelete.get();
                }
                else {
                    loInclusive = true;
                    lo = (K)lastKeyBeforeChunkDelete.get();
                }
                initChunk();
                if (nextChunkIter == null) {
                    throw new NoSuchElementException();
                }
                if (nextIndex == Chunk.NONE) {
                    throw new ConcurrentModificationException();
                }
            }

            if (nextIndex == Chunk.NONE) {
                throw new NoSuchElementException();
            }


            ByteBuffer bb = getKey(nextIndex, nextChunk);
            Handle currentHandle = nextHandle;
            setNextKey();
            return new Pair<>(bb, currentHandle);
        }

        //Make next* point to next value to return
        private void initChunk() {

            nextChunkIter = null;
            nextIndex = Chunk.NONE;

            if (!isDescending) {
                if (lo != null)
                    nextChunk = skiplist.floorEntry(lo).getValue();
                else
                    nextChunk = skiplist.floorEntry(minKey).getValue();
                if (nextChunk != null) {
                    nextChunkIter = lo != null ? nextChunk.ascendingIter(lo, loInclusive) : nextChunk.ascendingIter();
                }
            } else {
                nextChunk = hi != null ? skiplist.floorEntry(hi).getValue()
                        : skiplist.lastEntry().getValue();
                if (nextChunk != null) {
                    nextChunkIter = hi != null ? nextChunk.descendingIter(hi, hiInclusive) : nextChunk.descendingIter();
                }
            }
            lastKeyBeforeChunkDelete = new AtomicReference<>(nextChunk);
            nextChunk.signInIterator(this);
            setNextKey();
        }


        private Chunk<K, V> getNextChunk(Chunk<K, V> current) {
            if (!isDescending) {
                return current.next.getReference();
            } else {
                ByteBuffer serializedMinKey = current.minKey;
                Map.Entry<Object, Chunk<K, V>> entry = skiplist.lowerEntry(serializedMinKey);
                if (entry == null) {
                    return null;
                } else {
                    return entry.getValue();
                }
            }
        }


        private Chunk.ChunkIter getChunkIter(Chunk<K, V> current) {
            if (!isDescending) {
                return current.ascendingIter();
            } else {
                return current.descendingIter();
            }
        }


        /**
         * sets nextChunk, nextChunkIter, next and nextValue to the next key
         * if such a key doesn't exists then next is set to Chunk.NONE and nextValue is set to null
         */
        private void setNextKey() {

            Chunk<K, V> currentChunk = nextChunk;


            while (!nextChunkIter.hasNext()) { // chunks can have only removed keys
                nextChunk.signoutIterator(this);
                nextChunk = getNextChunk(nextChunk);
                if (nextChunk == null) { // there is no next chunk
                    nextIndex = Chunk.NONE;
                    nextHandle = null;
                    return;
                }
                nextChunkIter = getChunkIter(nextChunk);
            }

            //Must get nextIndex before return to avoid nextIndex==0 after init
            nextIndex = nextChunkIter.next();

            //
            boolean res = lastKeyBeforeChunkDelete.compareAndSet(currentChunk, nextChunk);
            if (res == false) {
                // failed to swap, rebalance on currentChunk updated lastKeyBeforeChunkDelete
                return;
            }

            //Must sign iterator after prev stages
            if (currentChunk != nextChunk) {
                nextChunk.signInIterator(this);
            }

            //Check if released for frozen and store last key
            if (nextChunk.state() == Chunk.State.RELEASED) {
                //save last key
                K lastKey = keySerializer.deserialize(getKey(nextIndex, nextChunk));
                setLastKey(nextChunk, lastKey);
                //No return to take care of first entrance after init
            }


            ByteBuffer key = getKey(nextIndex, nextChunk);
            if (!inBounds(key)) {
                // if we reached a key that is too high then there is no key in range
                nextIndex = Chunk.NONE;
                nextHandle = null;
                return;
            }

            // set next value
            nextHandle = nextChunk.getHandle(nextIndex);
            return;
        }


        ByteBuffer getKey(int ki, Chunk c) {
            return c.readKey(ki);
        }

        public boolean setLastKey(Object expect, Object update) {
            boolean ret = lastKeyBeforeChunkDelete.compareAndSet(expect, update);
            if (ret == true) {
                //System.out.println("WRITGO " + this + " " + update);
            }
            return ret;
        }
    }

    class ValueIterator extends Iter<OakRBuffer> {

        ValueIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending) {
            super(lo, loInclusive, hi, hiInclusive, isDescending);
        }

        @Override
        public OakRBuffer internalNext() {
            Handle handle = advance().getValue();
            if (handle == null)
                return null;

            return new OakRValueBufferImpl(handle);
        }
    }

    class ValueTransformIterator<T> extends Iter<T> {

        final Function<ByteBuffer, T> transformer;

        ValueTransformIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending,
                               Function<ByteBuffer, T> transformer) {
            super(lo, loInclusive, hi, hiInclusive, isDescending);
            this.transformer = transformer;
        }

        public T internalNext() {
            Handle handle = advance().getValue();
            if (handle == null) {
                return null;
            }
            handle.readLock.lock();
            T transformation = transformer.apply(handle.getImmutableByteBuffer());
            handle.readLock.unlock();
            return transformation;
        }
    }

    class EntryIterator extends Iter<Map.Entry<OakRBuffer, OakRBuffer>> {

        EntryIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending) {
            super(lo, loInclusive, hi, hiInclusive, isDescending);
        }

        public Map.Entry<OakRBuffer, OakRBuffer> internalNext() {
            Pair<ByteBuffer, Handle> pair = advance();
            if (pair.getValue() == null) {
                return null;
            }
            return new AbstractMap.SimpleImmutableEntry<>(new OakRKeyBufferImpl(pair.getKey()),
                    new OakRValueBufferImpl(pair.getValue()));
        }
    }

    class EntryTransformIterator<T> extends Iter<T> {

        final Function<Map.Entry<ByteBuffer, ByteBuffer>, T> transformer;

        EntryTransformIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending,
                               Function<Map.Entry<ByteBuffer, ByteBuffer>, T> transformer) {
            super(lo, loInclusive, hi, hiInclusive, isDescending);
            this.transformer = transformer;
        }

        public T internalNext() {

            Pair<ByteBuffer, Handle> pair = advance();
            Handle handle = pair.getValue();
            ByteBuffer serializedKey = pair.getKey();
            if (handle == null) {
                return null;
            }
            handle.readLock.lock();
            if (handle.isDeleted()) {
                handle.readLock.unlock();
                return null;
            }
            ByteBuffer serializedValue = handle.getImmutableByteBuffer();
            Map.Entry<ByteBuffer, ByteBuffer> entry = new AbstractMap.SimpleEntry<ByteBuffer, ByteBuffer>(serializedKey, serializedValue);
            if (serializedKey == null || serializedValue == null || entry == null || transformer == null) {
                handle.readLock.unlock();
                return null;
            }
            T transformation = transformer.apply(entry);
            handle.readLock.unlock();
            return transformation;
        }
    }

    class KeyIterator extends Iter<OakRBuffer> {

        KeyIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending) {
            super(lo, loInclusive, hi, hiInclusive, isDescending);
        }

        @Override
        public OakRBuffer internalNext() {

            Pair<ByteBuffer, Handle> pair = advance();
            ByteBuffer serializedKey = pair.getKey();
            serializedKey = serializedKey.slice(); // TODO can I get rid of this?
            return new OakRKeyBufferImpl(serializedKey);
        }
    }

    class KeyTransformIterator<T> extends Iter<T> {

        final Function<ByteBuffer, T> transformer;

        KeyTransformIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending,
                             Function<ByteBuffer, T> transformer) {
            super(lo, loInclusive, hi, hiInclusive, isDescending);
            this.transformer = transformer;
        }

        public T internalNext() {
            Pair<ByteBuffer, Handle> pair = advance();
            ByteBuffer serializedKey = pair.getKey();
            serializedKey = serializedKey.slice(); // TODO can I get rid of this?
            return transformer.apply(serializedKey);
        }
    }

    // Factory methods for iterators

    OakIterator<OakRBuffer> valuesBufferViewIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending) {
        return new ValueIterator(lo, loInclusive, hi, hiInclusive, isDescending);
    }

    OakIterator<Map.Entry<OakRBuffer, OakRBuffer>> entriesBufferViewIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending) {
        return new EntryIterator(lo, loInclusive, hi, hiInclusive, isDescending);
    }

    OakIterator<OakRBuffer> keysBufferViewIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending) {
        return new KeyIterator(lo, loInclusive, hi, hiInclusive, isDescending);
    }

    <T> OakIterator<T> valuesTransformIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending, Function<ByteBuffer, T> transformer) {
        return new ValueTransformIterator<>(lo, loInclusive, hi, hiInclusive, isDescending, transformer);
    }

    <T> OakIterator<T> entriesTransformIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending, Function<Map.Entry<ByteBuffer, ByteBuffer>, T> transformer) {
        return new EntryTransformIterator<>(lo, loInclusive, hi, hiInclusive, isDescending, transformer);
    }

    <T> OakIterator<T> keysTransformIterator(K lo, boolean loInclusive, K hi, boolean hiInclusive, boolean isDescending, Function<ByteBuffer, T> transformer) {
        return new KeyTransformIterator<>(lo, loInclusive, hi, hiInclusive, isDescending, transformer);
    }

}
