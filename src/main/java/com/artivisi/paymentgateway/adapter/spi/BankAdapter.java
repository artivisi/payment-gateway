package com.artivisi.paymentgateway.adapter.spi;

import com.artivisi.paymentgateway.entity.HostingModel;

/**
 * One bank's inbound protocol, declared so the gateway can tell whether it has an adapter for every
 * escrow it is configured to collect through.
 *
 * <p>An adapter is an ordinary Spring bean — a REST controller, a SOAP endpoint — that serves what
 * its bank calls and does its work through the core services ({@code EscrowResolver},
 * {@code InquiryService}, {@code PaymentApplicationService}, {@code BankCallbackRecorder}). Those
 * services and the entities they return are the contract between core and adapter; this interface
 * only adds the three facts the core cannot infer from a controller.
 *
 * <p>Bank protocols are the part of this product that is not ours to publish: a bank's integration
 * specification arrives under its own terms, and its message shapes, checksums and response codes
 * come straight out of that document. They therefore live in a private assembly that depends on this
 * engine, and this repository keeps only the contract and a worked example against an invented bank.
 * The seam is not decoration — it is the reason the engine can be public at all.
 */
public interface BankAdapter {

    /**
     * The value of {@code EscrowAccount.provider} this adapter serves. Matching is exact: an escrow
     * naming a provider no adapter claims is a deployment built without the adapter it needs.
     */
    String provider();

    /** Which side owns the VA number, and therefore which half of the protocol this adapter speaks. */
    HostingModel hostingModel();

    /** Where the bank reaches it, for the startup report and the operations runbook. */
    String inboundPath();
}
