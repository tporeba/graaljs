/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.access;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.ValueType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.array.JSArrayFirstElementIndexNode;
import com.oracle.truffle.js.nodes.array.JSArrayLastElementIndexNode;
import com.oracle.truffle.js.nodes.array.JSArrayNextElementIndexNode;
import com.oracle.truffle.js.nodes.array.JSArrayPreviousElementIndexNode;
import com.oracle.truffle.js.nodes.interop.JSForeignToJSTypeNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.builtins.JSArrayBufferView;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;
import com.oracle.truffle.js.runtime.util.JSClassProfile;

public abstract class ForEachIndexCallNode extends JavaScriptBaseNode {

    public abstract static class CallbackNode extends JavaScriptBaseNode {
        public abstract Object apply(long index, Object value, TruffleObject target, Object callback, Object callbackThisArg, Object currentResult);
    }

    @ValueType
    public static final class MaybeResult<T> {
        private final T result;
        private final boolean resultPresent;

        public MaybeResult(T result, boolean resultPresent) {
            this.result = result;
            this.resultPresent = resultPresent;
        }

        public static <T> MaybeResult<T> returnResult(T result) {
            return new MaybeResult<>(result, true);
        }

        public static <T> MaybeResult<T> continueResult(T result) {
            return new MaybeResult<>(result, false);
        }

        public boolean isPresent() {
            return resultPresent;
        }

        public T get() {
            return result;
        }
    }

    public abstract static class MaybeResultNode extends JavaScriptBaseNode {
        public abstract MaybeResult<Object> apply(long index, Object value, Object callbackResult, Object currentResult);
    }

    @Child private IsArrayNode isArrayNode = IsArrayNode.createIsAnyArray();
    protected final JSClassProfile targetClassProfile = JSClassProfile.create();
    protected final BranchProfile needLoop = BranchProfile.create();
    @Child private CallbackNode callbackNode;
    @Child protected MaybeResultNode maybeResultNode;

    @Child private ReadElementNode.ReadElementArrayDispatchNode readElementNode;
    @Child private JSArrayFirstElementIndexNode firstElementIndexNode;
    @Child private JSArrayLastElementIndexNode lastElementIndexNode;
    @Child private JSHasPropertyNode hasPropertyNode;
    @Child private JSForeignToJSTypeNode toJSTypeNode;
    @Child private InteropLibrary interop;
    protected final JSContext context;

    protected ForEachIndexCallNode(JSContext context, CallbackNode callbackArgumentsNode, MaybeResultNode maybeResultNode) {
        this.callbackNode = callbackArgumentsNode;
        this.maybeResultNode = maybeResultNode;
        this.context = context;
        this.readElementNode = ReadElementNode.ReadElementArrayDispatchNode.create();
    }

    public static ForEachIndexCallNode create(JSContext context, CallbackNode callbackArgumentsNode, MaybeResultNode maybeResultNode, boolean forward) {
        if (forward) {
            return new ForwardForEachIndexCallNode(context, callbackArgumentsNode, maybeResultNode);
        } else {
            return new BackwardForEachIndexCallNode(context, callbackArgumentsNode, maybeResultNode);
        }
    }

    public final Object executeForEachIndex(TruffleObject target, Object callback, Object callbackThisArg, long fromIndex, long length, Object initialResult) {
        boolean isArray = isArrayNode.execute(target);
        if (isArray && context.getArrayPrototypeNoElementsAssumption().isValid()) {
            return executeForEachIndexFast((DynamicObject) target, callback, callbackThisArg, fromIndex, length, isArray, initialResult);
        } else {
            return executeForEachIndexSlow(target, callback, callbackThisArg, fromIndex, length, initialResult);
        }
    }

    protected abstract Object executeForEachIndexFast(DynamicObject target, Object callback, Object callbackThisArg, long fromIndex, long length, boolean arrayCondition, Object initialResult);

    protected abstract Object executeForEachIndexSlow(TruffleObject target, Object callback, Object callbackThisArg, long fromIndex, long length, Object initialResult);

    protected final long firstElementIndex(DynamicObject target, long length) {
        if (firstElementIndexNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            firstElementIndexNode = insert(JSArrayFirstElementIndexNode.create(context));
        }
        return firstElementIndexNode.executeLong(target, length);
    }

    protected final long lastElementIndex(DynamicObject target, long length) {
        if (lastElementIndexNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            lastElementIndexNode = insert(JSArrayLastElementIndexNode.create(context));
        }
        return lastElementIndexNode.executeLong(target, length);
    }

    protected final InteropLibrary getInterop() {
        if (interop == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            interop = insert(InteropLibrary.getFactory().createDispatched(3));
        }
        return interop;
    }

    protected Object foreignRead(TruffleObject target, long index) {
        if (toJSTypeNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            toJSTypeNode = insert(JSForeignToJSTypeNode.create());
        }
        return JSInteropUtil.readArrayElementOrDefault(target, index, Undefined.instance, getInterop(), toJSTypeNode, this);
    }

    protected final void checkHasDetachedBuffer(TruffleObject view) {
        if (!context.getTypedArrayNotDetachedAssumption().isValid() && JSArrayBufferView.isJSArrayBufferView(view) && JSArrayBufferView.hasDetachedBuffer((DynamicObject) view)) {
            throw Errors.createTypeErrorDetachedBuffer();
        }
    }

    protected final Object callback(long index, Object value, TruffleObject target, Object callback, Object callbackThisArg, Object currentResult) {
        if (callbackNode == null) {
            return callbackThisArg;
        } else {
            return callbackNode.apply(index, value, target, callback, callbackThisArg, currentResult);
        }
    }

    protected final Object readElementInBounds(DynamicObject target, long index, boolean arrayCondition) {
        return readElementNode.executeArrayGet(target, JSObject.getArray(target, arrayCondition), index, target, Undefined.instance, arrayCondition, context);
    }

    protected final boolean hasProperty(TruffleObject target, long index) {
        if (hasPropertyNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            hasPropertyNode = insert(JSHasPropertyNode.create());
        }
        return hasPropertyNode.executeBoolean(target, index);
    }

    protected static final class ForwardForEachIndexCallNode extends ForEachIndexCallNode {
        private final ConditionProfile fromIndexZero = ConditionProfile.createBinaryProfile();

        @Child private JSArrayNextElementIndexNode nextElementIndexNode;

        public ForwardForEachIndexCallNode(JSContext context, CallbackNode callbackArgumentsNode, MaybeResultNode maybeResultNode) {
            super(context, callbackArgumentsNode, maybeResultNode);
        }

        @Override
        protected Object executeForEachIndexFast(DynamicObject target, Object callback, Object callbackThisArg, long fromIndex, long length, boolean arrayCondition, Object initialResult) {
            long index = fromIndexZero.profile(fromIndex == 0) ? firstElementIndex(target, length) : nextElementIndex(target, fromIndex - 1, length);
            Object currentResult = initialResult;
            if (index < length) {
                needLoop.enter();
                while (index < length && index <= lastElementIndex(target, length)) {
                    Object value = readElementInBounds(target, index, arrayCondition);
                    Object callbackResult = callback(index, value, target, callback, callbackThisArg, currentResult);
                    MaybeResult<Object> maybeResult = maybeResultNode.apply(index, value, callbackResult, currentResult);
                    checkHasDetachedBuffer(target);
                    currentResult = maybeResult.get();
                    if (maybeResult.isPresent()) {
                        break;
                    }
                    index = nextElementIndex(target, index, length);
                }
            }
            return currentResult;
        }

        @Override
        protected Object executeForEachIndexSlow(TruffleObject target, Object callback, Object callbackThisArg, long fromIndex, long length, Object initialResult) {
            Object currentResult = initialResult;
            boolean isForeign = JSRuntime.isForeignObject(target);
            if (isForeign) {
                if (!getInterop().hasArrayElements(target)) {
                    // Foreign object would not understand our read calls with int indices
                    return currentResult;
                }
            }
            for (long index = fromIndex; index < length; index++) {
                if (hasProperty(target, index)) {
                    Object value = isForeign ? foreignRead(target, index) : JSObject.get(target, index, targetClassProfile);
                    Object callbackResult = callback(index, value, target, callback, callbackThisArg, currentResult);
                    MaybeResult<Object> maybeResult = maybeResultNode.apply(index, value, callbackResult, currentResult);
                    checkHasDetachedBuffer(target);
                    currentResult = maybeResult.get();
                    if (maybeResult.isPresent()) {
                        break;
                    }
                }
            }
            return currentResult;
        }

        private long nextElementIndex(DynamicObject target, long currentIndex, long length) {
            if (nextElementIndexNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                nextElementIndexNode = insert(JSArrayNextElementIndexNode.create(context));
            }
            return nextElementIndexNode.executeLong(target, currentIndex, length);
        }
    }

    protected static final class BackwardForEachIndexCallNode extends ForEachIndexCallNode {
        @Child protected JSArrayPreviousElementIndexNode previousElementIndexNode;

        public BackwardForEachIndexCallNode(JSContext context, CallbackNode callbackArgumentsNode, MaybeResultNode maybeResultNode) {
            super(context, callbackArgumentsNode, maybeResultNode);
        }

        @Override
        protected Object executeForEachIndexFast(DynamicObject target, Object callback, Object callbackThisArg, long fromIndex, long length, boolean arrayCondition, Object initialResult) {
            assert fromIndex < length;
            long index = previousElementIndex(target, fromIndex + 1);
            // NB: cannot rely on lastElementIndex here: can be > length (e.g. arguments object)
            Object currentResult = initialResult;
            if (index >= 0) {
                needLoop.enter();
                while (index >= 0 && index >= firstElementIndex(target, length)) {
                    Object value = readElementInBounds(target, index, arrayCondition);
                    Object callbackResult = callback(index, value, target, callback, callbackThisArg, currentResult);
                    MaybeResult<Object> maybeResult = maybeResultNode.apply(index, value, callbackResult, currentResult);
                    checkHasDetachedBuffer(target);
                    currentResult = maybeResult.get();
                    if (maybeResult.isPresent()) {
                        break;
                    }
                    index = previousElementIndex(target, index);
                }
            }
            return currentResult;
        }

        @Override
        protected Object executeForEachIndexSlow(TruffleObject target, Object callback, Object callbackThisArg, long fromIndex, long length, Object initialResult) {
            Object currentResult = initialResult;
            boolean isForeign = JSRuntime.isForeignObject(target);
            if (isForeign) {
                if (!getInterop().hasArrayElements(target)) {
                    // Foreign object would not understand our read calls with int indices
                    return currentResult;
                }
            }

            for (long index = fromIndex; index >= 0; index--) {
                if (hasProperty(target, index)) {
                    Object value = isForeign ? foreignRead(target, index) : JSObject.get(target, index, targetClassProfile);
                    Object callbackResult = callback(index, value, target, callback, callbackThisArg, currentResult);
                    MaybeResult<Object> maybeResult = maybeResultNode.apply(index, value, callbackResult, currentResult);
                    checkHasDetachedBuffer(target);
                    currentResult = maybeResult.get();
                    if (maybeResult.isPresent()) {
                        break;
                    }
                }
            }
            return currentResult;
        }

        private long previousElementIndex(DynamicObject target, long currentIndex) {
            if (previousElementIndexNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                previousElementIndexNode = insert(JSArrayPreviousElementIndexNode.create(context));
            }
            return previousElementIndexNode.executeLong(target, currentIndex);
        }
    }
}
