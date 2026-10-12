package com.payflow.payment.security;

import java.util.UUID;

public record MerchantPrincipal(UUID merchantId, UUID credentialId, String externalMerchantId) {}
