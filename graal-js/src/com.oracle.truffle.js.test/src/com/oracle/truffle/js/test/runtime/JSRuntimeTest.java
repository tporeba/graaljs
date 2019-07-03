/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.test.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;

import org.junit.Test;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.binary.JSEqualNode;
import com.oracle.truffle.js.nodes.binary.JSIdenticalNode;
import com.oracle.truffle.js.nodes.unary.TypeOfNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.LargeInteger;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSBigInt;
import com.oracle.truffle.js.runtime.builtins.JSBoolean;
import com.oracle.truffle.js.runtime.builtins.JSDate;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSMap;
import com.oracle.truffle.js.runtime.builtins.JSNumber;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.builtins.JSSet;
import com.oracle.truffle.js.runtime.builtins.JSString;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.test.JSTest;
import com.oracle.truffle.js.test.polyglot.ForeignTestMap;

public class JSRuntimeTest extends JSTest {

    @Override
    public void setup() {
        super.setup();
        testHelper.enterContext();
    }

    @Override
    public void close() {
        testHelper.leaveContext();
        super.close();
    }

    @Test
    public void testEqual() {
        JSContext context = testHelper.getJSContext();
        DynamicObject date = JSDate.create(context, 42);
        assertTrue(JSRuntime.equal(date, JSDate.toString(42, testHelper.getRealm())));
        assertFalse(JSRuntime.equal(Null.instance, false));
        assertFalse(JSRuntime.equal(0, Null.instance));
        assertFalse(JSRuntime.equal(true, Undefined.instance));
        assertFalse(JSRuntime.equal(Undefined.instance, 1));
        assertTrue(JSRuntime.equal(Float.MAX_VALUE, Float.MAX_VALUE));
    }

    @Test
    public void testIdentical() {
        assertTrue(JSRuntime.identical(new BigInteger("9876543210"), new BigInteger("9876543210")));
        TruffleLanguage.Env env = testHelper.getRealm().getEnv();
        assertTrue(JSRuntime.identical(env.asGuestValue(BigInteger.ONE), env.asGuestValue(BigInteger.ONE)));
    }

    @Test
    public void testNumberToStringWorksForLargeInteger() {
        assertEquals("42", JSRuntime.numberToString(LargeInteger.valueOf(42)));
    }

    @Test
    public void testQuote() {
        char char6 = 6;
        char char30 = 30;
        assertEquals("\"aA1_\\u0006\\u001e\\b\\f\\n\\r\\t\\\\\\\"\"", JSRuntime.quote("aA1_" + char6 + char30 + "\b\f\n\r\t\\\""));
    }

    @Test
    public void testImportValue() {
        assertEquals(Null.instance, JSRuntime.importValue(null));

        assertEquals(42, JSRuntime.importValue(42));
        assertEquals("42", JSRuntime.importValue("42"));
        assertEquals(true, JSRuntime.importValue(true));
        assertEquals("X", JSRuntime.importValue('X'));

        // same for now, might not hold eternally
        assertSame(42, JSRuntime.importValue((byte) 42));
        assertSame(42, JSRuntime.importValue((short) 42));
    }

    @Test
    public void testIsIntegerIndex() {
        assertTrue(JSRuntime.isIntegerIndex(0L));
        assertTrue(JSRuntime.isIntegerIndex(1L));
        assertTrue(JSRuntime.isIntegerIndex(9007199254740990L));
        assertTrue(JSRuntime.isIntegerIndex(9007199254740991L));

        assertFalse(JSRuntime.isIntegerIndex(9007199254740992L));
        assertFalse(JSRuntime.isIntegerIndex(9007199254740993L));
    }

    private static <T extends JavaScriptNode> T adopt(T node) {
        assert node.isAdoptable();
        Truffle.getRuntime().createCallTarget(new RootNode(null) {
            @Child JavaScriptNode child = node;

            @Override
            public Object execute(VirtualFrame frame) {
                return child.execute(frame);
            }
        });
        return node;
    }

    @Test
    public void testEqualRuntimeAndNode() {
        JSEqualNode node = adopt(JSEqualNode.create());
        Object[] values = createValues(testHelper.getJSContext());

        for (int i = 0; i < values.length; i++) {
            Object v1 = values[i];
            for (int j = 0; j < values.length; j++) {
                Object v2 = values[j];
                boolean r1 = JSRuntime.equal(v1, v2);
                boolean r2 = node.executeBoolean(v1, v2);
                assertTrue("wrong outcode of equals for i=" + i + ", j=" + j, r1 == r2);
            }
        }
    }

    private static Object[] createValues(JSContext ctx) {
        return new Object[]{0, 1, true, false, 0.5, "foo", Symbol.SYMBOL_MATCH, Null.instance, Undefined.instance, JSString.create(ctx, "hallo"), JSNumber.create(ctx, 4711),
                        JSBoolean.create(ctx, true), JSUserObject.create(ctx), JSProxy.create(ctx, JSUserObject.create(ctx), JSUserObject.create(ctx)), JSBigInt.create(ctx, BigInt.ZERO),
                        new ForeignTestMap()};
    }

    @Test
    public void testIdenticalRuntimeAndNode() {
        JSIdenticalNode node = adopt(JSIdenticalNode.createStrictEqualityComparison());
        Object[] values = createValues(testHelper.getJSContext());

        for (int i = 0; i < values.length; i++) {
            Object v1 = values[i];
            for (int j = 0; j < values.length; j++) {
                Object v2 = values[j];
                boolean r1 = JSRuntime.identical(v1, v2);
                boolean r2 = node.executeBoolean(v1, v2);
                assertTrue("wrong outcode of identical for i=" + i + ", j=" + j, r1 == r2);
            }
        }
    }

    @Test
    public void testTypeofRuntimeAndNode() {
        TypeOfNode node = adopt(TypeOfNode.create());
        Object[] values = createValues(testHelper.getJSContext());

        for (int i = 0; i < values.length; i++) {
            Object v1 = values[i];
            String r1 = JSRuntime.typeof(v1);
            String r2 = node.executeString(v1);
            assertTrue("wrong outcode of typeof for i=" + i, r1.equals(r2));
        }
    }

    @Test
    public void testSafeToStringCollections() {
        DynamicObject map = JSMap.create(testHelper.getJSContext());
        JSMap.getInternalMap(map).put("foo", "bar");
        assertEquals("Map(1){\"foo\" => \"bar\"}", JSRuntime.safeToString(map));

        DynamicObject set = JSSet.create(testHelper.getJSContext());
        JSSet.getInternalSet(set).put("foo", "UNUSED");
        assertEquals("Set(1){\"foo\"}", JSRuntime.safeToString(set));
    }

    @Test
    public void testIsArrayIndex() {
        // Boxed Integer
        assertFalse(JSRuntime.isArrayIndex(Integer.valueOf(-1)));
        assertTrue(JSRuntime.isArrayIndex(Integer.valueOf(0)));
        assertTrue(JSRuntime.isArrayIndex(Integer.valueOf(Integer.MAX_VALUE)));
        // Boxed Double
        assertFalse(JSRuntime.isArrayIndex(Double.valueOf(-1)));
        assertTrue(JSRuntime.isArrayIndex(Double.valueOf(0)));
        assertTrue(JSRuntime.isArrayIndex(Double.valueOf(4294967294L)));
        assertFalse(JSRuntime.isArrayIndex(Double.valueOf(4294967295L)));
        // Boxed Long
        assertFalse(JSRuntime.isArrayIndex(Long.valueOf(-1)));
        assertTrue(JSRuntime.isArrayIndex(Long.valueOf(0)));
        assertTrue(JSRuntime.isArrayIndex(Long.valueOf(4294967294L)));
        assertFalse(JSRuntime.isArrayIndex(Long.valueOf(4294967295L)));
        // String
        assertFalse(JSRuntime.isArrayIndex("-1"));
        assertTrue(JSRuntime.isArrayIndex("0"));
        assertFalse(JSRuntime.isArrayIndex((Object) "-1"));
        assertTrue(JSRuntime.isArrayIndex((Object) "0"));
        assertTrue(JSRuntime.isArrayIndex((Object) "4294967294"));
        assertFalse(JSRuntime.isArrayIndex((Object) "4294967295"));
        assertFalse(JSRuntime.isArrayIndex((Object) "99999999999999999999999"));
        assertFalse(JSRuntime.isArrayIndex((Object) "NaN"));
        assertFalse(JSRuntime.isArrayIndex(null));
    }

    @Test
    public void testIsPrototypeOf() {
        JSContext ctx = testHelper.getJSContext();
        DynamicObject parent1 = JSUserObject.create(ctx);
        DynamicObject parent2 = JSUserObject.create(ctx);
        DynamicObject child1 = JSUserObject.createWithPrototype(parent1, ctx);
        DynamicObject grandchild1 = JSUserObject.createWithPrototype(child1, ctx);

        assertFalse(JSRuntime.isPrototypeOf(parent1, parent2));
        assertFalse(JSRuntime.isPrototypeOf(parent1, parent1));
        assertFalse(JSRuntime.isPrototypeOf(parent1, grandchild1));
        assertFalse(JSRuntime.isPrototypeOf(child1, grandchild1));
        assertFalse(JSRuntime.isPrototypeOf(grandchild1, grandchild1));

        assertTrue(JSRuntime.isPrototypeOf(child1, parent1));
        assertTrue(JSRuntime.isPrototypeOf(grandchild1, child1));
        assertTrue(JSRuntime.isPrototypeOf(grandchild1, parent1));
    }

    @Test
    public void testToPropertyKey() {
        // no conversion necessary
        assertTrue(JSRuntime.isPropertyKey(JSRuntime.toPropertyKey("test")));
        assertTrue(JSRuntime.isPropertyKey(JSRuntime.toPropertyKey(Symbol.SYMBOL_SEARCH)));

        // conversion necessary
        assertTrue(JSRuntime.isPropertyKey(JSRuntime.toPropertyKey(1)));
        assertTrue(JSRuntime.isPropertyKey(JSRuntime.toPropertyKey(true)));
        assertTrue(JSRuntime.isPropertyKey(JSRuntime.toPropertyKey(JSUserObject.create(testHelper.getJSContext()))));
    }

    @Test
    public void testCall() {
        JSContext ctx = testHelper.getJSContext();
        DynamicObject thisObj = JSUserObject.create(ctx);
        Object[] defaultArgs = new Object[]{"foo", 42, false};

        DynamicObject fnObj = JSFunction.create(ctx.getRealm(), JSFunctionData.createCallOnly(ctx, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(ctx.getLanguage(), null, null) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object[] args = frame.getArguments();
                return "" + JSArguments.getUserArgument(args, 0) + JSArguments.getUserArgument(args, 1) + JSArguments.getUserArgument(args, 2);
            }
        }), 0, "test"));

        assertEquals("foo42false", JSRuntime.call(fnObj, thisObj, defaultArgs));
        assertEquals("foo42false", JSRuntime.call(JSProxy.create(ctx, fnObj, JSUserObject.create(ctx)), thisObj, defaultArgs));
    }

    @Test
    public void testConstruct() {
        JSContext ctx = testHelper.getJSContext();
        DynamicObject arrayCtrFn = ctx.getRealm().getArrayConstructor().getFunctionObject();
        Object result = JSRuntime.construct(arrayCtrFn, new Object[]{10});
        assertTrue(JSArray.isJSArray(result));
        assertEquals(10, JSArray.arrayGetLength((DynamicObject) result));

        result = JSRuntime.construct(JSProxy.create(ctx, arrayCtrFn, JSUserObject.create(ctx)), new Object[]{10});
        assertTrue(JSArray.isJSArray(result));
        assertEquals(10, JSArray.arrayGetLength((DynamicObject) result));

        try {
            JSRuntime.construct(JSUserObject.create(ctx), new Object[]{10});
            assertTrue(false);
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("not a function"));
        }
    }
}
