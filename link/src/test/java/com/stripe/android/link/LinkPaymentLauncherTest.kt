package com.stripe.android.link

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.test.core.app.ApplicationProvider
import com.stripe.android.core.injection.WeakMapInjectorRegistry
import com.stripe.android.link.model.StripeIntentFixtures
import com.stripe.android.link.utils.FakeAndroidKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class LinkPaymentLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mockHostActivityLauncher = mock<ActivityResultLauncher<LinkActivityContract.Args>>()

    private var linkPaymentLauncher = LinkPaymentLauncher(
        context,
        setOf(PRODUCT_USAGE),
        { PUBLISHABLE_KEY },
        { STRIPE_ACCOUNT_ID },
        enableLogging = true,
        ioContext = Dispatchers.IO,
        uiContext = mock(),
        paymentAnalyticsRequestFactory = mock(),
        analyticsRequestExecutor = mock(),
        stripeRepository = mock(),
        addressResourceRepository = mock()
    )

    init {
        FakeAndroidKeyStore.setup()
    }

    @Test
    fun `verify present() launches LinkActivity with correct arguments`() =
        runTest {
            launch {
                val stripeIntent = StripeIntentFixtures.PI_SUCCEEDED
                linkPaymentLauncher.setup(
                    configuration = LinkPaymentLauncher.Configuration(
                        stripeIntent,
                        MERCHANT_NAME,
                        CUSTOMER_EMAIL,
                        CUSTOMER_PHONE,
                        CUSTOMER_NAME,
                        null
                    ),
                    coroutineScope = this
                )
                linkPaymentLauncher.present(mockHostActivityLauncher)

                verify(mockHostActivityLauncher).launch(
                    argWhere { arg ->
                        arg.stripeIntent == stripeIntent &&
                            arg.merchantName == MERCHANT_NAME &&
                            arg.customerEmail == CUSTOMER_EMAIL &&
                            arg.customerPhone == CUSTOMER_PHONE &&
                            arg.customerName == CUSTOMER_NAME &&
                            arg.injectionParams != null &&
                            arg.injectionParams.productUsage == setOf(PRODUCT_USAGE) &&
                            arg.injectionParams.injectorKey == LinkPaymentLauncher::class.simpleName + WeakMapInjectorRegistry.CURRENT_REGISTER_KEY.get() &&
                            arg.injectionParams.enableLogging &&
                            arg.injectionParams.publishableKey == PUBLISHABLE_KEY &&
                            arg.injectionParams.stripeAccountId.equals(STRIPE_ACCOUNT_ID)
                    }
                )

                // Need to cancel because the coroutine scope is still collecting the account status
                this.coroutineContext.job.cancel()
            }
        }

    companion object {
        const val PRODUCT_USAGE = "productUsage"
        const val PUBLISHABLE_KEY = "publishableKey"
        const val STRIPE_ACCOUNT_ID = "stripeAccountId"

        const val MERCHANT_NAME = "merchantName"
        const val CUSTOMER_EMAIL = "email"
        const val CUSTOMER_PHONE = "phone"
        const val CUSTOMER_NAME = "name"
    }
}
