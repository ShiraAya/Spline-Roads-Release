package com.sora.splineroads.core;

import java.util.*;

/** Immutable insertion-ordered map. Updates share unchanged AVL subtrees, so taking a
 * planning snapshot never copies an entire city. Both lookup and update are O(log n).
 * Replacing a key retains its order; removing and re-inserting appends it, like LinkedHashMap.
 * Values must themselves be immutable before a snapshot is handed to another thread. */
public final class ImmutableOrderedMap<K extends Comparable<? super K>,V> extends AbstractMap<K,V> {
  private record Item<K,V>(long order,K key,V value) implements Map.Entry<K,V> {
    public K getKey(){return key;}
    public V getValue(){return value;}
    public V setValue(V ignored){throw new UnsupportedOperationException("immutable snapshot");}
    @Override public boolean equals(Object o){return o instanceof Map.Entry<?,?> e&&Objects.equals(key,e.getKey())&&Objects.equals(value,e.getValue());}
    @Override public int hashCode(){return Objects.hashCode(key)^Objects.hashCode(value);}
  }
  private static final class Node<K,V> {
    final K key;final V value;final Node<K,V> left,right;final int height,size;
    Node(K key,V value,Node<K,V> left,Node<K,V> right){this.key=key;this.value=value;this.left=left;this.right=right;this.height=1+Math.max(height(left),height(right));this.size=1+size(left)+size(right);}
  }
  private final Node<K,Item<K,V>> lookup;
  private final Node<Long,Item<K,V>> ordered;
  private final long nextOrder;
  private ImmutableOrderedMap(Node<K,Item<K,V>> lookup,Node<Long,Item<K,V>> ordered,long nextOrder){this.lookup=lookup;this.ordered=ordered;this.nextOrder=nextOrder;}
  public static <K extends Comparable<? super K>,V> ImmutableOrderedMap<K,V> empty(){return new ImmutableOrderedMap<>(null,null,0);}
  @Override public int size(){return size(lookup);}
  @SuppressWarnings("unchecked")
  private Item<K,V> item(Object key){
    if(!(key instanceof Comparable<?>))return null;
    try{return find(lookup,(K)key);}catch(ClassCastException wrongKeyType){return null;}
  }
  @Override public V get(Object key){var found=item(key);return found==null?null:found.value();}
  @Override public boolean containsKey(Object key){return item(key)!=null;}
  public ImmutableOrderedMap<K,V> with(K key,V value){
    Objects.requireNonNull(key);Objects.requireNonNull(value);var before=find(lookup,key);
    if(before!=null&&before.value()==value)return this;
    if(before==null&&nextOrder==Long.MAX_VALUE)throw new IllegalStateException("snapshot insertion sequence exhausted");
    var item=new Item<K,V>(before==null?nextOrder:before.order(),key,value);
    return new ImmutableOrderedMap<>(put(lookup,key,item),put(ordered,item.order(),item),before==null?nextOrder+1:nextOrder);
  }
  public ImmutableOrderedMap<K,V> without(K key){
    Objects.requireNonNull(key);var before=find(lookup,key);if(before==null)return this;
    if(size()==1)return empty();
    return new ImmutableOrderedMap<>(remove(lookup,key),remove(ordered,before.order()),nextOrder);
  }
  @Override public Set<Map.Entry<K,V>> entrySet(){
    return new AbstractSet<>(){
      @Override public int size(){return ImmutableOrderedMap.this.size();}
      @Override public Iterator<Map.Entry<K,V>> iterator(){
        return new Iterator<>(){
          final Deque<Node<Long,Item<K,V>>> stack=new ArrayDeque<>();
          {push(ordered);}
          private void push(Node<Long,Item<K,V>> node){while(node!=null){stack.push(node);node=node.left;}}
          public boolean hasNext(){return !stack.isEmpty();}
          public Map.Entry<K,V> next(){if(stack.isEmpty())throw new NoSuchElementException();var node=stack.pop();push(node.right);return node.value;}
        };
      }
    };
  }
  private static int height(Node<?,?> n){return n==null?0:n.height;}
  private static int size(Node<?,?> n){return n==null?0:n.size;}
  private static <K extends Comparable<? super K>,V> V find(Node<K,V> node,K key){
    while(node!=null){int c=key.compareTo(node.key);if(c==0)return node.value;node=c<0?node.left:node.right;}return null;
  }
  private static <K extends Comparable<? super K>,V> Node<K,V> put(Node<K,V> node,K key,V value){
    if(node==null)return new Node<>(key,value,null,null);int c=key.compareTo(node.key);
    if(c==0)return new Node<>(key,value,node.left,node.right);
    return balance(c<0?new Node<>(node.key,node.value,put(node.left,key,value),node.right):new Node<>(node.key,node.value,node.left,put(node.right,key,value)));
  }
  private static <K extends Comparable<? super K>,V> Node<K,V> remove(Node<K,V> node,K key){
    if(node==null)return null;int c=key.compareTo(node.key);
    if(c<0)return balance(new Node<>(node.key,node.value,remove(node.left,key),node.right));
    if(c>0)return balance(new Node<>(node.key,node.value,node.left,remove(node.right,key)));
    if(node.left==null)return node.right;if(node.right==null)return node.left;
    var successor=node.right;while(successor.left!=null)successor=successor.left;
    return balance(new Node<>(successor.key,successor.value,node.left,remove(node.right,successor.key)));
  }
  private static <K,V> Node<K,V> balance(Node<K,V> node){
    int delta=height(node.left)-height(node.right);
    if(delta>1){var left=node.left;if(height(left.left)<height(left.right))node=new Node<>(node.key,node.value,rotateLeft(left),node.right);return rotateRight(node);}
    if(delta< -1){var right=node.right;if(height(right.right)<height(right.left))node=new Node<>(node.key,node.value,node.left,rotateRight(right));return rotateLeft(node);}
    return node;
  }
  private static <K,V> Node<K,V> rotateLeft(Node<K,V> node){var right=node.right;return new Node<>(right.key,right.value,new Node<>(node.key,node.value,node.left,right.left),right.right);}
  private static <K,V> Node<K,V> rotateRight(Node<K,V> node){var left=node.left;return new Node<>(left.key,left.value,left.left,new Node<>(node.key,node.value,left.right,node.right));}
}
