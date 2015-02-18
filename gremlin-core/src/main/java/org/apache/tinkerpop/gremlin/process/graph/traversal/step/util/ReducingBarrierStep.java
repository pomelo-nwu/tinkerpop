/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.graph.traversal.step.util;

import org.apache.tinkerpop.gremlin.process.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.process.Step;
import org.apache.tinkerpop.gremlin.process.Traversal;
import org.apache.tinkerpop.gremlin.process.Traverser;
import org.apache.tinkerpop.gremlin.process.computer.KeyValue;
import org.apache.tinkerpop.gremlin.process.computer.MapReduce;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.util.StaticMapReduce;
import org.apache.tinkerpop.gremlin.process.traversal.step.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.MapReducer;
import org.apache.tinkerpop.gremlin.process.traversal.step.Reducing;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.process.util.TraverserSet;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.io.Serializable;
import java.util.Iterator;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public abstract class ReducingBarrierStep<S, E> extends AbstractStep<S, E> implements MapReducer {

    private Supplier<E> seedSupplier;
    private BiFunction<E, Traverser<S>, E> reducingBiFunction;
    private boolean done = false;
    private boolean byPass =false;

    public ReducingBarrierStep(final Traversal.Admin traversal) {
        super(traversal);
    }

    public void setSeedSupplier(final Supplier<E> seedSupplier) {
        this.seedSupplier = seedSupplier;
    }

    public void setBiFunction(final BiFunction<E, Traverser<S>, E> reducingBiFunction) {
        this.reducingBiFunction = reducingBiFunction;
    }

    public Supplier<E> getSeedSupplier() {
        return this.seedSupplier;
    }

    public BiFunction<E, Traverser<S>, E> getBiFunction() {
        return this.reducingBiFunction;
    }

    public void byPass() {
        this.byPass = true;
    }

    @Override
    public void reset() {
        super.reset();
        this.done = false;
    }

    @Override
    public Traverser<E> processNextStart() {
        if(this.byPass) {
          return (Traverser<E>) this.starts.next();
        } else {
            if (this.done)
                throw FastNoSuchElementException.instance();
            E seed = this.seedSupplier.get();
            while (this.starts.hasNext())
                seed = this.reducingBiFunction.apply(seed, this.starts.next());
            this.done = true;
            return TraversalHelper.getRootTraversal(this.getTraversal()).getTraverserGenerator().generate(Reducing.FinalGet.tryFinalGet(seed), (Step) this, 1l);
        }
    }

    @Override
    public ReducingBarrierStep<S, E> clone() throws CloneNotSupportedException {
        final ReducingBarrierStep<S, E> clone = (ReducingBarrierStep<S, E>) super.clone();
        clone.done = false;
        return clone;
    }

    @Override
    public MapReduce getMapReduce() {
        return new DefaultMapReduce();
    }

    ///////

    public static class ObjectBiFunction<S, E> implements BiFunction<E, Traverser<S>, E>, Serializable {

        private final BiFunction<E, S, E> biFunction;

        public ObjectBiFunction(final BiFunction<E, S, E> biFunction) {
            this.biFunction = biFunction;
        }

        public final BiFunction<E, S, E> getBiFunction() {
            return this.biFunction;
        }

        @Override
        public E apply(final E seed, final Traverser<S> traverser) {
            return this.biFunction.apply(seed, traverser.get());
        }

    }

    ///////

    public class DefaultMapReduce extends StaticMapReduce {

        @Override
        public boolean doStage(Stage stage) {
            return !stage.equals(Stage.COMBINE);
        }

        @Override
        public String getMemoryKey() {
            return Graph.Hidden.hide("reducingBarrier");
        }

        @Override
        public Object generateFinalResult(final Iterator keyValues) {
            return ((KeyValue) keyValues.next()).getValue();

        }

        @Override
        public void map(final Vertex vertex, final MapEmitter emitter) {
            vertex.<TraverserSet<?>>property(TraversalVertexProgram.HALTED_TRAVERSERS).ifPresent(traverserSet -> traverserSet.forEach(emitter::emit));
        }

        @Override
        public void reduce(final Object key, final Iterator values, final ReduceEmitter emitter) {
            Object mutatingSeed = getSeedSupplier().get();
            final BiFunction function = getBiFunction();
            final boolean onTraverser = true;
            while (values.hasNext()) {
                mutatingSeed = function.apply(mutatingSeed, onTraverser ? values.next() : ((Traverser) values.next()).get());
            }
            emitter.emit(key, getTraversal().getTraverserGenerator().generate(Reducing.FinalGet.tryFinalGet(mutatingSeed), (Step) getTraversal().getEndStep(), 1l));
        }

    }

}
