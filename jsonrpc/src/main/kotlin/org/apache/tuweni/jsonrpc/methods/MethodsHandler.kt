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
package org.apache.tuweni.jsonrpc.methods

import com.netflix.concurrency.limits.limit.FixedLimit
import com.netflix.concurrency.limits.limiter.SimpleLimiter
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.common.Labels
import org.apache.tuweni.eth.JSONRPCRequest
import org.apache.tuweni.eth.JSONRPCResponse
import org.apache.tuweni.eth.methodNotEnabled
import org.apache.tuweni.eth.methodNotFound
import org.apache.tuweni.eth.tooManyRequests

class MethodsRouter(val methodsMap: Map<String, (JSONRPCRequest) -> JSONRPCResponse>) {

  fun handleRequest(request: JSONRPCRequest): JSONRPCResponse {
    val methodHandler = methodsMap[request.method]
    if (methodHandler == null) {
      return methodNotFound
    } else {
      return methodHandler(request)
    }
  }
}

class MeteredHandler(private val successCounter: LongCounter, private val failCounter: LongCounter, private val delegateHandler: (JSONRPCRequest) -> JSONRPCResponse) {

  fun handleRequest(request: JSONRPCRequest): JSONRPCResponse {
    val resp = delegateHandler(request)
    val labels = Labels.of("method", request.method)
    if (resp.error != null) {
      failCounter.add(1, labels)
    } else {
      successCounter.add(1, labels)
    }
    return resp
  }
}

class MethodAllowListHandler(private val allowedMethods: List<String>, private val delegateHandler: (JSONRPCRequest) -> JSONRPCResponse) {

  fun handleRequest(request: JSONRPCRequest): JSONRPCResponse {
    var found = false
    for (method in allowedMethods) {
      if (request.method.startsWith(method)) {
        found = true
        break
      }
    }
    if (!found) {
      return methodNotEnabled.copy(id = request.id)
    }
    return delegateHandler(request)
  }
}

class ThrottlingHandler(private val threshold: Int, private val delegateHandler: (JSONRPCRequest) -> JSONRPCResponse) {

  private val limiter: SimpleLimiter<Void> = SimpleLimiter
    .newBuilder()
    .limit(FixedLimit.of(threshold))
    .build()

  fun handleRequest(request: JSONRPCRequest): JSONRPCResponse {
    val listener = limiter.acquire(null)
    if (listener.isEmpty) {
      return tooManyRequests.copy(id = request.id)
    } else {
      try {
        val response = delegateHandler(request)
        listener.get().onSuccess()
        return response
      } catch (t: Throwable) {
        listener.get().onDropped()
        throw RuntimeException(t)
      }
    }
  }
}

// TODO DelegateHandler - choose from a number of handlers to see which to delegate to.
// TODO FilterHandler - filter incoming requests per allowlist
// TODO CachingHandler - cache some incoming requests
