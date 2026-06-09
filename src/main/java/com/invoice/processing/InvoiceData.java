package com.invoice.processing;

public class InvoiceData {

    private String vendorName;
    private String invoiceDate;
    private String invoiceId;
    private String subtotal;
    private String total;
    private Float vendorConfidence;
    private Float totalConfidence;
    private Float invoiceIdConfidence;
    private Float dateConfidence;
    public InvoiceData() {
    }

    public InvoiceData(String vendorName,
                       String invoiceDate,
                       String invoiceId,
                       String subtotal,
                       String total) {
        this.vendorName = vendorName;
        this.invoiceDate = invoiceDate;
        this.invoiceId = invoiceId;
        this.subtotal = subtotal;
        this.total = total;
    }

    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    public String getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(String invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(String invoiceId) {
        this.invoiceId = invoiceId;
    }

    public String getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(String subtotal) {
        this.subtotal = subtotal;
    }

    public String getTotal() {
        return total;
    }

    public void setTotal(String total) {
        this.total = total;
    }

    public Float getVendorConfidence() {
        return vendorConfidence;
    }

    public void setVendorConfidence(Float vendorConfidence) {
        this.vendorConfidence = vendorConfidence;
    }

    public Float getTotalConfidence() {
        return totalConfidence;
    }

    public void setTotalConfidence(Float totalConfidence) {
        this.totalConfidence = totalConfidence;
    }

    public Float getInvoiceIdConfidence() {
        return invoiceIdConfidence;
    }

    public void setInvoiceIdConfidence(Float invoiceIdConfidence) {
        this.invoiceIdConfidence = invoiceIdConfidence;
    }

    public Float getDateConfidence() {
        return dateConfidence;
    }

    public void setDateConfidence(Float dateConfidence) {
        this.dateConfidence = dateConfidence;
    }

    @Override
    public String toString() {
        return "InvoiceData{" +
                "vendorName='" + vendorName + '\'' +
                ", invoiceDate='" + invoiceDate + '\'' +
                ", invoiceId='" + invoiceId + '\'' +
                ", subtotal='" + subtotal + '\'' +
                ", total='" + total + '\'' +
                ", vendorConfidence=" + vendorConfidence +
                ", totalConfidence=" + totalConfidence +
                ", invoiceIdConfidence=" + invoiceIdConfidence +
                ", dateConfidence=" + dateConfidence +
                '}';
    }
}