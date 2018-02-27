package com.turn.ttorrent.common;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.NoSuchElementException;

public final class Optional<T> {

  private static final Optional<?> EMPTY = new Optional();

  @Nullable
  private final T value;

  public Optional(@NotNull T value) {
    this.value = value;
  }

  private Optional() {
    this.value = null;
  }

  @NotNull
  @SuppressWarnings("unchecked")
  public static <T> Optional<T> of(@Nullable T value) {
    return value == null ? (Optional<T>) EMPTY : new Optional<T>(value);
  }

  @NotNull
  public T get() throws NoSuchElementException {
    if (value == null) {
      throw new NoSuchElementException("No value present");
    }
    return value;
  }

  public boolean isPresent() {
    return value != null;
  }

  @NotNull
  public T orElse(@NotNull T defaultValue) {
    return value != null ? value : defaultValue;
  }

}
