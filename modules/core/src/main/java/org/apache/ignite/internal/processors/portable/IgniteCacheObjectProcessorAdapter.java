/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.portable;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import java.math.*;
import java.util.*;

/**
 *
 */
public abstract class IgniteCacheObjectProcessorAdapter extends GridProcessorAdapter implements GridPortableProcessor {
    /** */
    private static final sun.misc.Unsafe UNSAFE = GridUnsafe.unsafe();

    /** Immutable classes. */
    private static final Collection<Class<?>> IMMUTABLE_CLS = new HashSet<>();

    /** */
    private final GridBoundedConcurrentLinkedHashMap<Class<?>, Boolean> reflectionCache =
            new GridBoundedConcurrentLinkedHashMap<>(1024, 1024);

    /**
     *
     */
    static {
        IMMUTABLE_CLS.add(String.class);
        IMMUTABLE_CLS.add(Boolean.class);
        IMMUTABLE_CLS.add(Byte.class);
        IMMUTABLE_CLS.add(Short.class);
        IMMUTABLE_CLS.add(Character.class);
        IMMUTABLE_CLS.add(Integer.class);
        IMMUTABLE_CLS.add(Long.class);
        IMMUTABLE_CLS.add(Float.class);
        IMMUTABLE_CLS.add(Double.class);
        IMMUTABLE_CLS.add(UUID.class);
        IMMUTABLE_CLS.add(IgniteUuid.class);
        IMMUTABLE_CLS.add(BigDecimal.class);
    }

    /**
     * @param ctx Context.
     */
    public IgniteCacheObjectProcessorAdapter(GridKernalContext ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Nullable @Override public CacheObject prepareForCache(@Nullable CacheObject obj, GridCacheContext cctx) {
        if (obj == null)
            return null;

        return obj.prepareForCache(cctx.cacheObjectContext());
    }

    /** {@inheritDoc} */
    @Override public byte[] marshal(CacheObjectContext ctx, Object val) throws IgniteCheckedException {
        return CU.marshal(ctx.kernalContext().cache().context(), val);
    }

    /** {@inheritDoc} */
    @Override public Object unmarshal(CacheObjectContext ctx, byte[] bytes, ClassLoader clsLdr)
        throws IgniteCheckedException
    {
        return ctx.kernalContext().cache().context().marshaller().unmarshal(bytes, clsLdr);
    }

    /** {@inheritDoc} */
    @Nullable public KeyCacheObject toCacheKeyObject(CacheObjectContext ctx, Object obj) {
        if (obj instanceof KeyCacheObject)
            return (KeyCacheObject)obj;

        return new UserKeyCacheObjectImpl(obj, null);
    }

    /** {@inheritDoc} */
    @Override public CacheObject toCacheObject(GridCacheContext ctx, long valPtr, boolean tmp)
        throws IgniteCheckedException
    {
        assert valPtr != 0;

        int size = UNSAFE.getInt(valPtr);

        byte type = UNSAFE.getByte(valPtr + 4);

        byte[] bytes = U.copyMemory(valPtr + 5, size);

        if (ctx.kernalContext().config().isPeerClassLoadingEnabled() &&
            ctx.offheapTiered() &&
            type != CacheObjectAdapter.TYPE_BYTE_ARR) {
            IgniteUuid valClsLdrId = U.readGridUuid(valPtr + 5 + size);

            ClassLoader ldr =
                valClsLdrId != null ? ctx.deploy().getClassLoader(valClsLdrId) : ctx.deploy().localLoader();

            return toCacheObject(ctx.cacheObjectContext(), unmarshal(ctx.cacheObjectContext(), bytes, ldr));
        }
        else
            return toCacheObject(ctx.cacheObjectContext(), type, bytes);
    }

    /** {@inheritDoc} */
    @Override public CacheObject toCacheObject(CacheObjectContext ctx, byte type, byte[] bytes) {
        switch (type) {
            case CacheObjectAdapter.TYPE_BYTE_ARR:
                return new CacheObjectImpl(bytes, null);

            case CacheObjectAdapter.TYPE_REGULAR:
                return new CacheObjectImpl(null, bytes);
        }

        throw new IllegalArgumentException("Invalid object type: " + type);
    }

    /** {@inheritDoc} */
    @Nullable @Override public CacheObject toCacheObject(CacheObjectContext ctx, @Nullable Object obj) {
        if (obj == null || obj instanceof CacheObject)
            return (CacheObject)obj;

        return new UserCacheObjectImpl(obj);
    }

    /** {@inheritDoc} */
    @Override public CacheObjectContext contextForCache(ClusterNode node, @Nullable String cacheName) {
        CacheConfiguration ccfg = null;

        for (CacheConfiguration ccfg0 : ctx.config().getCacheConfiguration()) {
            if (F.eq(cacheName, ccfg0.getName())) {
                ccfg = ccfg0;

                break;
            }
        }

        return new CacheObjectContext(ctx,
            new GridCacheDefaultAffinityKeyMapper(),
            ccfg != null && ccfg.isCopyOnGet(),
            ccfg != null && ccfg.isQueryIndexEnabled());
    }

    /** {@inheritDoc} */
    @Override public boolean immutable(Object obj) {
        assert obj != null;

        Class<?> cls = obj.getClass();

        if (IMMUTABLE_CLS.contains(cls))
            return true;

        Boolean immutable = reflectionCache.get(cls);

        if (immutable != null)
            return immutable;

        immutable = IgniteUtils.hasAnnotation(cls, IgniteImmutable.class);

        reflectionCache.putIfAbsent(cls, immutable);

        return immutable;
    }
}
