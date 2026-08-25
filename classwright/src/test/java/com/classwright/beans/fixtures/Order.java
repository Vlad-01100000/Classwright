package com.classwright.beans.fixtures;

/** A conventional JavaBean with one property of each interesting shape. */
public class Order {

    private String id;
    private int quantity;
    private long total;
    private double weight;
    private boolean urgent;
    private String[] tags;
    private String readOnly = "fixed";
    private String writeOnly;

    public Order() {
    }

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

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    /** A boolean property, which conventionally reads as {@code isX}. */
    public boolean isUrgent() {
        return urgent;
    }

    public void setUrgent(boolean urgent) {
        this.urgent = urgent;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    /** Getter only. */
    public String getReadOnly() {
        return readOnly;
    }

    /** Setter only. */
    public void setWriteOnly(String writeOnly) {
        this.writeOnly = writeOnly;
    }

    /** Not a property; here to confirm arbitrary methods are ignored. */
    public String describe() {
        return id + "/" + quantity;
    }

    public String peekWriteOnly() {
        return writeOnly;
    }
}
