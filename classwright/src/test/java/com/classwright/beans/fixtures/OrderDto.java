package com.classwright.beans.fixtures;

/**
 * The copy target. Deliberately not identical to {@link Order}:
 *
 * <ul>
 *   <li>{@code id}, {@code quantity} and {@code urgent} match exactly and should copy.</li>
 *   <li>{@code total} is a {@code String} here and a {@code long} there, so it should copy only
 *       through a converter.</li>
 *   <li>{@code missing} exists only here, and {@code weight} only there; both should be skipped.</li>
 * </ul>
 */
public class OrderDto {

    private String id;
    private int quantity;
    private String total;
    private boolean urgent;
    private String missing;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getTotal() {
        return total;
    }

    public void setTotal(String total) {
        this.total = total;
    }

    public boolean isUrgent() {
        return urgent;
    }

    public void setUrgent(boolean urgent) {
        this.urgent = urgent;
    }

    public String getMissing() {
        return missing;
    }

    public void setMissing(String missing) {
        this.missing = missing;
    }
}
