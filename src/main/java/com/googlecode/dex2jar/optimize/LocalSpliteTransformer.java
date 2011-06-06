/*
 * Copyright (c) 2009-2011 Panxiaobo
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.dex2jar.optimize;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * @author Panxiaobo [pxb1988 at gmail.com]
 * 
 */
public class LocalSpliteTransformer implements MethodTransformer, Opcodes {

    static class ValueBox {
        public Local local;

        /**
         * @param local
         */
        public ValueBox(Local local) {
            super();
            this.local = local;
        }
    }

    static class Local {
        public int index;
        boolean noTouch = false;

        /**
         * @param index
         * @param noTouch
         */
        public Local(int index, boolean noTouch) {
            super();
            this.index = index;
            this.noTouch = noTouch;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.googlecode.dex2jar.optimize.MethodTransformer#transform(org.objectweb.asm.tree.MethodNode)
     */
    @Override
    public void transform(MethodNode method) {
        InsnList il = method.instructions;
        Map<Integer, ValueBox>[] frames = new HashMap[il.size()];
        int index = 0;
        Map<Integer, ValueBox> init = new HashMap();
        if ((method.access & ACC_STATIC) == 0) {
            init.put(index, new ValueBox(new Local(index, true)));
            index++;
        }
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            init.put(index, new ValueBox(new Local(index, true)));
            index++;
        }
        frames[0] = init;
        AbstractInsnNode[] nodes = il.toArray();
        ValueBox[] vbs = new ValueBox[nodes.length];
        Set<LabelNode>[] exs = new Set[nodes.length];

        for (Iterator it = method.tryCatchBlocks.iterator(); it.hasNext();) {
            TryCatchBlockNode tcbn = (TryCatchBlockNode) it.next();
            for (AbstractInsnNode p = tcbn.start.getNext(); p != null && p != tcbn.end; p = p.getNext()) {
                int i = il.indexOf(p);
                Set<LabelNode> set = exs[i];
                if (set == null) {
                    set = new HashSet();
                    exs[i] = set;
                }
                set.add(tcbn.handler);
            }
        }

        index = Short.MAX_VALUE;

        Map<Integer, ValueBox> tmp = new HashMap();
        for (int i = 0; i < nodes.length; i++) {
            if (frames[i] == null) {
                frames[i] = new HashMap();
            }
            AbstractInsnNode node = nodes[i];

            if (Util.isWrite(node)) {
                Map<Integer, ValueBox> frame = frames[i];
                tmp.clear();
                tmp.putAll(frame);
                ValueBox vb = new ValueBox(new Local(index++, false));
                vbs[i] = vb;
                tmp.put(Util.var(node), vb);
                merge(il, node, tmp, frames, exs[i]);
            } else if (Util.isRead(node)) {
                vbs[i] = frames[i].get(Util.var(node));
                merge(il, node, null, frames, exs[i]);
            } else {
                merge(il, node, null, frames, exs[i]);
            }
        }

        for (int i = 0; i < nodes.length; i++) {
            AbstractInsnNode node = nodes[i];
            ValueBox vb = vbs[i];
            if (vb != null) {
                Util.var(node, vbs[i].local.index);
            }
        }

    }

    /**
     * @param il
     * @param node
     * @param tmp
     * @param frames
     * @param exs
     */
    private void merge(InsnList il, AbstractInsnNode node, Map<Integer, ValueBox> tmp, Map<Integer, ValueBox>[] frames, Set<LabelNode> exs) {
        int index = il.indexOf(node);
        int opcode = node.getOpcode();
        if (opcode == GOTO) {
            merge(il, frames, tmp, index, ((JumpInsnNode) node).label);
        } else if (opcode >= 153 && opcode <= 166) {// jmp
            merge(il, frames, tmp, index, node.getNext());
            merge(il, frames, tmp, index, ((JumpInsnNode) node).label);
        } else if (opcode == LOOKUPSWITCH) {
            LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) node;
            merge(il, frames, tmp, index, lsin.dflt);
            for (Iterator it = lsin.labels.iterator(); it.hasNext();) {
                LabelNode ln = (LabelNode) it.next();
                merge(il, frames, tmp, index, ln);
            }
        } else if (opcode == TABLESWITCH) {
            TableSwitchInsnNode tsin = (TableSwitchInsnNode) node;
            merge(il, frames, tmp, index, tsin.dflt);
            for (Iterator it = tsin.labels.iterator(); it.hasNext();) {
                LabelNode ln = (LabelNode) it.next();
                merge(il, frames, tmp, index, ln);
            }
        } else if (Util.isEnd(node)) {
            //
        } else {
            merge(il, frames, tmp, index, node.getNext());
            if (exs != null) {
                for (LabelNode ln : exs) {
                    merge(il, frames, tmp, index, ln);
                }
            }
        }
    }

    /**
     * @param il
     * @param frames
     * @param tmp
     * @param index
     * @param indexOf
     */
    private void merge(InsnList il, Map<Integer, ValueBox>[] frames, Map<Integer, ValueBox> tmp, int index, AbstractInsnNode dist) {
        if (dist == null) {
            return;
        }
        if (tmp == null) {
            tmp = frames[index];
        }
        int distIndex = il.indexOf(dist);

        Map<Integer, ValueBox> distFrame = frames[distIndex];
        if (distFrame == null) {
            distFrame = new HashMap();
            distFrame.putAll(tmp);
            frames[distIndex] = distFrame;
        } else {
            for (Integer i : tmp.keySet()) {
                ValueBox a = tmp.get(i);
                ValueBox b = distFrame.get(i);
                if (b == null) {
                    distFrame.put(i, a);
                } else if (a.local != b.local) {
                    if (a.local.noTouch) {
                        b.local = a.local;
                    } else if (b.local.noTouch) {
                        a.local = b.local;
                    } else {
                        a.local = b.local;
                    }
                }
            }
        }

    }
}
