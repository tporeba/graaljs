/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.util.List;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.builtins.helper.ListGetNode;
import com.oracle.truffle.js.builtins.helper.ListSizeNode;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;
import com.oracle.truffle.js.runtime.util.JSClassProfile;

public abstract class CopyDataPropertiesNode extends JavaScriptBaseNode {
    protected final JSContext context;
    private final boolean withExcluded;

    protected CopyDataPropertiesNode(JSContext context, boolean withExcluded) {
        this.context = context;
        this.withExcluded = withExcluded;
    }

    public static CopyDataPropertiesNode create(JSContext context, boolean withExcluded) {
        return CopyDataPropertiesNodeGen.create(context, withExcluded);
    }

    public final Object execute(Object target, Object source) {
        return execute(target, source, null);
    }

    public abstract Object execute(Object target, Object source, Object[] excludedItems);

    @SuppressWarnings("unused")
    @Specialization(guards = "isNullOrUndefined(value)")
    protected static DynamicObject doNullOrUndefined(DynamicObject target, Object value, Object[] excludedItems) {
        return target;
    }

    @Specialization(guards = {"isJSObject(source)"})
    protected final DynamicObject copyDataProperties(DynamicObject target, DynamicObject source, Object[] excludedItems,
                    @Cached("create(context)") ReadElementNode getNode,
                    @Cached("create(false)") JSGetOwnPropertyNode getOwnProperty,
                    @Cached ListSizeNode listSize,
                    @Cached ListGetNode listGet,
                    @Cached JSClassProfile classProfile) {
        List<Object> ownPropertyKeys = JSObject.ownPropertyKeys(source, classProfile);
        int size = listSize.execute(ownPropertyKeys);
        for (int i = 0; i < size; i++) {
            Object nextKey = listGet.execute(ownPropertyKeys, i);
            assert JSRuntime.isPropertyKey(nextKey);
            if (!isExcluded(excludedItems, nextKey)) {
                PropertyDescriptor desc = getOwnProperty.execute(source, nextKey);
                if (desc != null && desc.getEnumerable()) {
                    Object propValue = getNode.executeWithTargetAndIndex(source, nextKey);
                    JSRuntime.createDataProperty(target, nextKey, propValue);
                }
            }
        }
        return target;
    }

    private boolean isExcluded(Object[] excludedKeys, Object key) {
        if (withExcluded) {
            for (Object e : excludedKeys) {
                assert JSRuntime.isPropertyKey(e);
                if (e.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Specialization(guards = {"!isJSType(from)"}, limit = "3")
    protected final DynamicObject copyDataPropertiesForeign(DynamicObject target, Object from, Object[] excludedItems,
                    @CachedLibrary("from") InteropLibrary objInterop,
                    @CachedLibrary(limit = "3") InteropLibrary keysInterop,
                    @CachedLibrary(limit = "3") InteropLibrary stringInterop) {
        if (objInterop.isNull(from)) {
            return target;
        }
        try {
            Object members = objInterop.getMembers(from);
            long length = JSInteropUtil.getArraySize(members, keysInterop, this);
            for (long i = 0; i < length; i++) {
                Object key = keysInterop.readArrayElement(members, i);
                assert InteropLibrary.getFactory().getUncached().isString(key);
                String stringKey = key instanceof String ? (String) key : stringInterop.asString(key);
                if (!isExcluded(excludedItems, stringKey)) {
                    Object value = objInterop.readMember(from, stringKey);
                    JSRuntime.createDataProperty(target, stringKey, JSRuntime.importValue(value));
                }
            }
        } catch (UnsupportedMessageException | InvalidArrayIndexException | UnknownIdentifierException e) {
            throw Errors.createTypeErrorInteropException(from, e, "CopyDataProperties", this);
        }
        return target;
    }
}
