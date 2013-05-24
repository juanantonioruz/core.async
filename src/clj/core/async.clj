;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns core.async
  (:require [core.async.impl.protocols :as impl]
            [core.async.impl.channels :as channels]
            [core.async.impl.buffers :as buffers]
            [core.async.impl.timers :as timers]
            [core.async.impl.dispatch :as dispatch]
            [core.async.impl.ioc-macros :as ioc]
            [core.async.impl.ioc-alt]
            [core.async.impl.alt-helpers :refer :all]))

(set! *warn-on-reflection* true)

(defn- fn-handler
  [f]
  (reify
   impl/Locking
   (lock [_])
   (unlock [_])
   
   impl/Handler
   (active? [_] true)
   (lock-id [_] 0)
   (commit [_] f)))

(defn buffer
  "Returns a fixed buffer of size n. When full, puts will block/park."
  [n]
  (buffers/fixed-buffer n))

(defn dropping-buffer
  "Returns a buffer of size n. When full, puts will complete but
  val will be dropped (no transfer)."
  [n]
  (buffers/dropping-buffer n))

(defn sliding-buffer
  "Returns a buffer of size n. When full, puts will complete, and be
  buffered, but oldest elements in buffer will be dropped (not
  transferred)."
  [n]
  (buffers/sliding-buffer n))

(defn chan
  "Creates a channel with an optional buffer. If buf-or-n is a number,
  will create and use a fixed buffer of that size."
  ([] (chan nil))
  ([buf-or-n] (channels/chan (if (number? buf-or-n) (buffer buf-or-n) buf-or-n))))

(defn timeout
  "Returns a channel that will close after msecs"
  [msecs]
  (timers/timeout msecs))

(defn <!
  "takes a val from port. Will return nil if closed. Will block/park
  if nothing is available. Can participate in alt"
  [port]
  (let [p (promise)
        cb (impl/take! port (fn-handler (fn [v] (deliver p v))))]
    (when cb (cb))
    (deref p)))

(defn take!
  "Asynchronously takes a val from port, passing to fn1. Will pass nil
   if closed. If on-caller? (default true) is true, and value is
   immediately available, will call fn1 on calling thread.
   Returns nil."
  ([port fn1] (take! port fn1 true))
  ([port fn1 on-caller?]
     (let [cb (impl/take! port (fn-handler fn1))]
       (when cb
         (if on-caller?
           (cb)
           (dispatch/run cb)))
       nil)))

(defn >!
  "puts a val into port. Will block/park if no buffer space is
  available. Returns nil. Can participate in alt"
  [port val]
  (let [p (promise)
        cb (impl/put! port val (fn-handler (fn [] (deliver p nil))))]
    (when cb (cb))
    (deref p)
    nil))

(defn put!
  "Asynchronously puts a val into port, calling fn0 when
   complete. Will throw if closed. If on-caller? (default true) is
   true, and the put is immediately accepted, will call fn0 on calling
   thread.  Returns nil."
  ([port val fn0] (put! port val fn0 true))
  ([port val fn0 on-caller?]
     (let [cb (impl/put! port val (fn-handler fn0))]
       (when cb
         (if on-caller?
           (cb)
           (dispatch/run cb)))
       nil)))

(defn close!
  "Closes a channel. The channel will no longer accept any puts (they
  will throw). Data in the channel remains avaiable for taking, until
  exhausted, after which takes will return nil. If there are any
  pending takes, they will be dispatched with nil. Closing a closed
  channel is a no-op. Returns nil."
  [chan]
  (impl/close! chan))


(defn do-alt [clauses]
  (assert (even? (count clauses)) "unbalanced clauses")
  (let [clauses (partition 2 clauses)
        default (first (filter #(= :default (first %)) clauses))
        clauses (remove #(= :default (first %)) clauses)]
    (assert (every? keyword? (map first clauses)) "alt clauses must begin with keywords")
    (assert (every? sequential? (map second clauses)) "alt exprs must be async calls")
    (assert (every? #{"<!" ">!"} (map #(-> % second first name) clauses)) "alt exprs must be <! or >!")
    (let [gp (gensym)
          gflag (gensym)
          ops (map (fn [[label [op port arg]]]
                     (case (name op)
                           "<!" `(fn [] (impl/take! ~port (alt-handler ~gflag (fn [val#] (deliver ~gp [~label val#])))))
                           ">!" `(fn [] (impl/put! ~port  ~arg (alt-handler ~gflag (fn [] (deliver ~gp [~label nil])))))))
                   clauses)
          defops (when default
                  `((impl/lock ~gflag)
                    (let [got# (and (impl/active? ~gflag) (impl/commit ~gflag))]
                      (impl/unlock ~gflag)
                      (when got#
                        (deliver ~gp [:blah ~(second default)])))))]
      `(let [~gp (promise)
             ~gflag (alt-flag)
             ops# [~@ops]
             n# ~(count clauses)
             idxs# (random-array n#)]
         (loop [i# 0]
           (when (< i# n#)
             (let [idx# (nth idxs# i#)
                   cb# ((nth ops# idx#))]
               (if cb#
                 (cb#)
                 (recur (inc i#))))))
         ~@defops
         (deref ~gp)))))

(defmacro alt
  "Makes a non-deterministic choice between one of several channel operations (<! and/or >!)

  Each clause takes the form of:

  :keyword-label channel-op

  where channel op is (<! port-expr) or (>! port-expr val-expr)

  The label :default is reserved, and its argument can be any
  expression.  If more than one of the operations is ready to
  complete, a pseudo-random choice is made. If none of the operations
  are ready to complete, and a :default clause is provided, [:default
  val-of-expression] will be returned. Else alt will block/park until
  any one of the operations is ready to complete, and its label and
  value (if any) are returned. At most one of the operations will
  complete.

  alt returns a vector of [:chosen-label taken-val], taken-val being
  nil for >! ops and closed channels.

  Note: there is no guarantee that the port-exps or val-exprs will be
  used, nor in what order should they be, so they should not be
  depended upon for side effects."

  [& clauses]
  (do-alt clauses))

(defmacro async
  "Asynchronously executes the body, returning immediately to the
  calling thread. Additionally, any visible calls to <!, >! and alt
  channel operations within the body will block (if necessary) by
  'parking' the calling thread rather than tying up an OS thread (or
  the only JS thread when in ClojureScript). Upon completion of the
  operation, the body will be resumed.

  Returns a channel which will receive the result of the body when
  completed"
  [& body]
  (binding [ioc/*symbol-translations* '{alt core.async.impl.ioc-alt/alt}]
    `(let [f# ~(ioc/state-machine body)
           c# (chan 1)
           state# (-> (f#)
                      (assoc ::ioc/chan c#))]
       (ioc/async-chan-wrapper state#)
       c#)))
