package com.turn.ttorrent.common;

public class Pair<A, B> {

  private final A myFirst;
  private final B mySecond;

  public Pair(A first, B second) {
    myFirst = first;
    mySecond = second;
  }

  public A first() {
    return myFirst;
  }

  public B second() {
    return mySecond;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Pair<?, ?> pair = (Pair<?, ?>) o;

    if (!myFirst.equals(pair.myFirst)) return false;
    return mySecond.equals(pair.mySecond);
  }

  @Override
  public int hashCode() {
    int result = myFirst.hashCode();
    result = 31 * result + mySecond.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "Pair{" +
            "myFirst=" + myFirst +
            ", mySecond=" + mySecond +
            '}';
  }
}
