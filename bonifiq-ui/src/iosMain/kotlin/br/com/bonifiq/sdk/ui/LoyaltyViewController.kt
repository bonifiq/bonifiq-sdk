package br.com.bonifiq.sdk.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import br.com.bonifiq.sdk.BonifiQ
import platform.UIKit.UIViewController

/**
 * Hub de fidelidade para apps iOS nativos (SwiftUI/UIKit).
 *
 * ```swift
 * let vc = LoyaltyViewControllerKt.LoyaltyViewController(bonifiq: bonifiq, cartId: orderFormId) { event in
 *     if let apply = event as? LoyaltyEventApplyCoupon { cart.apply(coupon: apply.couponCode) }
 * }
 * ```
 */
public fun LoyaltyViewController(
    bonifiq: BonifiQ,
    cartId: String?,
    onEvent: (LoyaltyEvent) -> Unit,
): UIViewController = ComposeUIViewController {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            BonifiQLoyalty(bonifiq = bonifiq, cartId = cartId, onEvent = onEvent)
        }
    }
}
