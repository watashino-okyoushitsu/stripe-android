package com.stripe.android.paymentsheet.flowcontroller

import android.app.Activity
import android.content.Context
import android.os.Parcelable
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import com.stripe.android.PaymentConfiguration
import com.stripe.android.core.injection.ENABLE_LOGGING
import com.stripe.android.core.injection.Injectable
import com.stripe.android.core.injection.Injector
import com.stripe.android.core.injection.InjectorKey
import com.stripe.android.core.injection.UIContext
import com.stripe.android.core.injection.WeakMapInjectorRegistry
import com.stripe.android.googlepaylauncher.GooglePayEnvironment
import com.stripe.android.googlepaylauncher.GooglePayPaymentMethodLauncher
import com.stripe.android.googlepaylauncher.GooglePayPaymentMethodLauncherContract
import com.stripe.android.googlepaylauncher.injection.GooglePayPaymentMethodLauncherFactory
import com.stripe.android.link.LinkActivityContract
import com.stripe.android.link.LinkActivityResult
import com.stripe.android.link.LinkPaymentLauncher
import com.stripe.android.link.model.AccountStatus
import com.stripe.android.model.ConfirmPaymentIntentParams
import com.stripe.android.model.ConfirmSetupIntentParams
import com.stripe.android.model.PaymentIntent
import com.stripe.android.payments.core.injection.PRODUCT_USAGE
import com.stripe.android.payments.paymentlauncher.PaymentLauncher
import com.stripe.android.payments.paymentlauncher.PaymentLauncherContract
import com.stripe.android.payments.paymentlauncher.PaymentResult
import com.stripe.android.payments.paymentlauncher.StripePaymentLauncherAssistedFactory
import com.stripe.android.paymentsheet.PaymentOptionCallback
import com.stripe.android.paymentsheet.PaymentOptionContract
import com.stripe.android.paymentsheet.PaymentOptionResult
import com.stripe.android.paymentsheet.PaymentOptionsViewModel
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.PaymentSheetResultCallback
import com.stripe.android.paymentsheet.addresselement.AddressDetails
import com.stripe.android.paymentsheet.addresselement.toConfirmPaymentIntentShipping
import com.stripe.android.paymentsheet.addresselement.toIdentifierMap
import com.stripe.android.paymentsheet.analytics.EventReporter
import com.stripe.android.paymentsheet.forms.FormViewModel
import com.stripe.android.paymentsheet.injection.DaggerFlowControllerComponent
import com.stripe.android.paymentsheet.injection.FlowControllerComponent
import com.stripe.android.paymentsheet.model.ClientSecret
import com.stripe.android.paymentsheet.model.ConfirmStripeIntentParamsFactory
import com.stripe.android.paymentsheet.model.PaymentIntentClientSecret
import com.stripe.android.paymentsheet.model.PaymentOption
import com.stripe.android.paymentsheet.model.PaymentOptionFactory
import com.stripe.android.paymentsheet.model.PaymentSelection
import com.stripe.android.paymentsheet.model.SavedSelection
import com.stripe.android.paymentsheet.model.SetupIntentClientSecret
import com.stripe.android.paymentsheet.repositories.CustomerApiRepository
import com.stripe.android.paymentsheet.validate
import com.stripe.android.ui.core.address.AddressRepository
import com.stripe.android.ui.core.forms.resources.LpmRepository
import com.stripe.android.ui.core.forms.resources.ResourceRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.security.InvalidParameterException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

@FlowPreview
@Singleton
internal class DefaultFlowController @Inject internal constructor(
    // Properties provided through FlowControllerComponent.Builder
    private val lifecycleScope: CoroutineScope,
    lifecycleOwner: LifecycleOwner,
    private val statusBarColor: () -> Int?,
    private val paymentOptionFactory: PaymentOptionFactory,
    private val paymentOptionCallback: PaymentOptionCallback,
    private val paymentResultCallback: PaymentSheetResultCallback,
    activityResultCaller: ActivityResultCaller,
    @InjectorKey private val injectorKey: String,
    // Properties provided through injection
    private val flowControllerInitializer: FlowControllerInitializer,
    private val customerApiRepository: CustomerApiRepository,
    private val eventReporter: EventReporter,
    private val viewModel: FlowControllerViewModel,
    private val paymentLauncherFactory: StripePaymentLauncherAssistedFactory,
    // even though unused this forces Dagger to initialize it here.
    private val lpmResourceRepository: ResourceRepository<LpmRepository>,
    private val addressResourceRepository: ResourceRepository<AddressRepository>,
    /**
     * [PaymentConfiguration] is [Lazy] because the client might set publishableKey and
     * stripeAccountId after creating a [DefaultFlowController].
     */
    private val lazyPaymentConfiguration: Provider<PaymentConfiguration>,
    @UIContext private val uiContext: CoroutineContext,
    @Named(ENABLE_LOGGING) private val enableLogging: Boolean,
    @Named(PRODUCT_USAGE) private val productUsage: Set<String>,
    private val googlePayPaymentMethodLauncherFactory: GooglePayPaymentMethodLauncherFactory,
    private val linkLauncher: LinkPaymentLauncher
) : PaymentSheet.FlowController, Injector {
    private val paymentOptionActivityLauncher: ActivityResultLauncher<PaymentOptionContract.Args>
    private val googlePayActivityLauncher:
        ActivityResultLauncher<GooglePayPaymentMethodLauncherContract.Args>
    private val linkActivityResultLauncher:
        ActivityResultLauncher<LinkActivityContract.Args>

    /**
     * [FlowControllerComponent] is hold to inject into [Activity]s and created
     * after [DefaultFlowController].
     */
    lateinit var flowControllerComponent: FlowControllerComponent

    private var paymentLauncher: PaymentLauncher? = null

    private val resourceRepositories = listOf(lpmResourceRepository, addressResourceRepository)

    override fun inject(injectable: Injectable<*>) {
        when (injectable) {
            is PaymentOptionsViewModel.Factory -> {
                flowControllerComponent.inject(injectable)
            }
            is FormViewModel.Factory -> {
                flowControllerComponent.inject(injectable)
            }
            else -> {
                throw IllegalArgumentException("invalid Injectable $injectable requested in $this")
            }
        }
    }

    init {
        lifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    paymentLauncher = paymentLauncherFactory.create(
                        { lazyPaymentConfiguration.get().publishableKey },
                        { lazyPaymentConfiguration.get().stripeAccountId },
                        activityResultCaller.registerForActivityResult(
                            PaymentLauncherContract(),
                            ::onPaymentResult
                        )
                    )
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    paymentLauncher = null
                }
            }
        )

        paymentOptionActivityLauncher =
            activityResultCaller.registerForActivityResult(
                PaymentOptionContract(),
                ::onPaymentOptionResult
            )
        googlePayActivityLauncher =
            activityResultCaller.registerForActivityResult(
                GooglePayPaymentMethodLauncherContract(),
                ::onGooglePayResult
            )
        linkActivityResultLauncher =
            activityResultCaller.registerForActivityResult(
                LinkActivityContract(),
                ::onLinkActivityResult
            )
    }

    override fun configureWithPaymentIntent(
        paymentIntentClientSecret: String,
        configuration: PaymentSheet.Configuration?,
        callback: PaymentSheet.FlowController.ConfigCallback
    ) {
        configureInternal(
            PaymentIntentClientSecret(paymentIntentClientSecret),
            configuration,
            callback
        )
    }

    override fun configureWithSetupIntent(
        setupIntentClientSecret: String,
        configuration: PaymentSheet.Configuration?,
        callback: PaymentSheet.FlowController.ConfigCallback
    ) {
        configureInternal(
            SetupIntentClientSecret(setupIntentClientSecret),
            configuration,
            callback
        )
    }

    private fun configureInternal(
        clientSecret: ClientSecret,
        configuration: PaymentSheet.Configuration?,
        callback: PaymentSheet.FlowController.ConfigCallback
    ) {
        try {
            configuration?.validate()
            clientSecret.validate()
        } catch (e: InvalidParameterException) {
            callback.onConfigured(success = false, e)
            return
        }

        lifecycleScope.launch {
            val result = flowControllerInitializer.init(
                clientSecret,
                configuration
            )

            // Wait until all required resources are loaded before completing initialization.
            resourceRepositories.forEach { it.waitUntilLoaded() }

            if (isActive) {
                dispatchResult(result, callback)
            } else {
                callback.onConfigured(false, null)
            }
        }
    }

    override fun getPaymentOption(): PaymentOption? {
        return viewModel.paymentSelection?.let {
            paymentOptionFactory.create(it)
        }
    }

    override fun presentPaymentOptions() {
        val initData = runCatching {
            viewModel.initData
        }.getOrElse {
            error(
                "FlowController must be successfully initialized using " +
                    "configureWithPaymentIntent() or configureWithSetupIntent() " +
                    "before calling presentPaymentOptions()"
            )
        }

        paymentOptionActivityLauncher.launch(
            PaymentOptionContract.Args(
                stripeIntent = initData.stripeIntent,
                paymentMethods = initData.paymentMethods,
                config = initData.config,
                isGooglePayReady = initData.isGooglePayReady,
                newLpm = viewModel.paymentSelection as? PaymentSelection.New,
                statusBarColor = statusBarColor(),
                injectorKey = injectorKey,
                enableLogging = enableLogging,
                productUsage = productUsage
            )
        )
    }

    override fun confirm() {
        val initData = runCatching {
            viewModel.initData
        }.getOrElse {
            error(
                "FlowController must be successfully initialized using " +
                    "configureWithPaymentIntent() or configureWithSetupIntent() " +
                    "before calling confirm()"
            )
        }

        when (val paymentSelection = viewModel.paymentSelection) {
            PaymentSelection.GooglePay -> launchGooglePay(initData)
            PaymentSelection.Link,
            is PaymentSelection.New.LinkInline -> confirmLink(paymentSelection, initData)
            else -> confirmPaymentSelection(paymentSelection, initData)
        }
    }

    @VisibleForTesting
    fun confirmPaymentSelection(
        paymentSelection: PaymentSelection?,
        initData: InitData
    ) {
        val confirmParamsFactory =
            ConfirmStripeIntentParamsFactory.createFactory(
                initData.clientSecret,
                initData.config?.shippingDetails?.toConfirmPaymentIntentShipping()
            )

        when (paymentSelection) {
            is PaymentSelection.Saved -> {
                confirmParamsFactory.create(paymentSelection)
            }
            is PaymentSelection.New -> {
                confirmParamsFactory.create(paymentSelection)
            }
            else -> null
        }?.let { confirmParams ->
            lifecycleScope.launch {
                when (confirmParams) {
                    is ConfirmPaymentIntentParams -> {
                        paymentLauncher?.confirm(confirmParams)
                    }
                    is ConfirmSetupIntentParams -> {
                        paymentLauncher?.confirm(confirmParams)
                    }
                }
            }
        }
    }

    internal fun onGooglePayResult(
        googlePayResult: GooglePayPaymentMethodLauncher.Result
    ) {
        when (googlePayResult) {
            is GooglePayPaymentMethodLauncher.Result.Completed -> {
                runCatching {
                    viewModel.initData
                }.fold(
                    onSuccess = { initData ->
                        val paymentSelection = PaymentSelection.Saved(
                            googlePayResult.paymentMethod,
                            isGooglePay = true
                        )
                        viewModel.paymentSelection = paymentSelection
                        confirmPaymentSelection(
                            paymentSelection,
                            initData
                        )
                    },
                    onFailure = {
                        eventReporter.onPaymentFailure(PaymentSelection.GooglePay)
                        paymentResultCallback.onPaymentSheetResult(
                            PaymentSheetResult.Failed(it)
                        )
                    }
                )
            }
            is GooglePayPaymentMethodLauncher.Result.Failed -> {
                eventReporter.onPaymentFailure(PaymentSelection.GooglePay)
                paymentResultCallback.onPaymentSheetResult(
                    PaymentSheetResult.Failed(
                        GooglePayException(
                            googlePayResult.error
                        )
                    )
                )
            }
            is GooglePayPaymentMethodLauncher.Result.Canceled -> {
                // don't log cancellations as failures
                paymentResultCallback.onPaymentSheetResult(PaymentSheetResult.Canceled)
            }
        }
    }

    private fun onLinkActivityResult(result: LinkActivityResult) =
        onPaymentResult(result.convertToPaymentResult())

    private suspend fun dispatchResult(
        result: FlowControllerInitializer.InitResult,
        callback: PaymentSheet.FlowController.ConfigCallback
    ) = withContext(uiContext) {
        when (result) {
            is FlowControllerInitializer.InitResult.Success -> {
                onInitSuccess(result.initData, callback)
            }
            is FlowControllerInitializer.InitResult.Failure -> {
                callback.onConfigured(false, result.throwable)
            }
        }
    }

    private fun onInitSuccess(
        initData: InitData,
        callback: PaymentSheet.FlowController.ConfigCallback
    ) {
        eventReporter.onInit(initData.config)

        when (val savedString = initData.savedSelection) {
            SavedSelection.GooglePay -> PaymentSelection.GooglePay
            SavedSelection.Link -> PaymentSelection.Link
            is SavedSelection.PaymentMethod ->
                initData.paymentMethods.firstOrNull {
                    it.id == savedString.id
                }?.let {
                    PaymentSelection.Saved(it)
                }
            else -> null
        }.let {
            viewModel.paymentSelection = it
        }

        viewModel.initData = initData
        callback.onConfigured(true, null)
    }

    @JvmSynthetic
    internal fun onPaymentOptionResult(
        paymentOptionResult: PaymentOptionResult?
    ) {
        paymentOptionResult?.paymentMethods?.let {
            viewModel.initData = viewModel.initData.copy(paymentMethods = it)
        }
        when (paymentOptionResult) {
            is PaymentOptionResult.Succeeded -> {
                val paymentSelection = paymentOptionResult.paymentSelection
                viewModel.paymentSelection = paymentSelection
                paymentOptionCallback.onPaymentOption(
                    paymentOptionFactory.create(
                        paymentSelection
                    )
                )
            }
            is PaymentOptionResult.Failed, is PaymentOptionResult.Canceled -> {
                paymentOptionCallback.onPaymentOption(
                    viewModel.paymentSelection?.let {
                        paymentOptionFactory.create(it)
                    }
                )
            }
            else -> {
                viewModel.paymentSelection = null
                paymentOptionCallback.onPaymentOption(null)
            }
        }
    }

    internal fun onPaymentResult(paymentResult: PaymentResult) {
        logPaymentResult(paymentResult)
        lifecycleScope.launch {
            paymentResultCallback.onPaymentSheetResult(
                paymentResult.convertToPaymentSheetResult()
            )
        }
    }

    private fun logPaymentResult(paymentResult: PaymentResult?) {
        when (paymentResult) {
            is PaymentResult.Completed -> {
                if ((viewModel.paymentSelection as? PaymentSelection.Saved)?.isGooglePay == true) {
                    // Google Pay is treated as a saved PM after confirmation
                    eventReporter.onPaymentSuccess(PaymentSelection.GooglePay)
                } else {
                    eventReporter.onPaymentSuccess(viewModel.paymentSelection)
                }
            }
            is PaymentResult.Failed -> eventReporter.onPaymentFailure(viewModel.paymentSelection)
            else -> {}
        }
    }

    private fun confirmLink(
        paymentSelection: PaymentSelection,
        initData: InitData
    ) {
        val config = requireNotNull(initData.config)

        lifecycleScope.launch {
            val shippingDetails: AddressDetails? = config.shippingDetails
            val customerPhone = if (shippingDetails?.isCheckboxSelected == true) {
                shippingDetails.phoneNumber
            } else {
                config.defaultBillingDetails?.phone
            }
            val shippingAddress = if (shippingDetails?.isCheckboxSelected == true) {
                shippingDetails.toIdentifierMap(config.defaultBillingDetails)
            } else {
                null
            }
            val customerEmail = config.defaultBillingDetails?.email ?: config.customer?.let {
                customerApiRepository.retrieveCustomer(
                    it.id,
                    it.ephemeralKeySecret
                )?.email
            }
            val accountStatus = linkLauncher.setup(
                configuration = LinkPaymentLauncher.Configuration(
                    stripeIntent = initData.stripeIntent,
                    merchantName = config.merchantDisplayName,
                    customerEmail = customerEmail,
                    customerPhone = customerPhone,
                    customerName = config.defaultBillingDetails?.name,
                    shippingValues = shippingAddress
                ),
                coroutineScope = lifecycleScope
            )
            // If a returning user is paying with a new card inline, launch Link to complete payment
            (paymentSelection as? PaymentSelection.New.LinkInline)?.takeIf {
                accountStatus == AccountStatus.Verified
            }?.linkPaymentDetails?.originalParams?.let {
                linkLauncher.present(linkActivityResultLauncher, it)
            } ?: run {
                if (paymentSelection is PaymentSelection.Link) {
                    // User selected Link as the payment method, not inline
                    linkLauncher.present(linkActivityResultLauncher)
                } else {
                    // New user paying inline, complete without launching Link
                    confirmPaymentSelection(paymentSelection, initData)
                }
            }
        }
    }

    private fun launchGooglePay(initData: InitData) {
        // initData.config.googlePay is guaranteed not to be null or GooglePay would be disabled
        val config = requireNotNull(initData.config)
        val googlePayConfig = requireNotNull(config.googlePay)
        val googlePayPaymentLauncherConfig = GooglePayPaymentMethodLauncher.Config(
            environment = when (googlePayConfig.environment) {
                PaymentSheet.GooglePayConfiguration.Environment.Production ->
                    GooglePayEnvironment.Production
                else ->
                    GooglePayEnvironment.Test
            },
            merchantCountryCode = googlePayConfig.countryCode,
            merchantName = config.merchantDisplayName
        )

        googlePayPaymentMethodLauncherFactory.create(
            lifecycleScope = lifecycleScope,
            config = googlePayPaymentLauncherConfig,
            readyCallback = {},
            activityResultLauncher = googlePayActivityLauncher,
            skipReadyCheck = true
        ).present(
            currencyCode = (initData.stripeIntent as? PaymentIntent)?.currency
                ?: googlePayConfig.currencyCode.orEmpty(),
            amount = (initData.stripeIntent as? PaymentIntent)?.amount?.toInt() ?: 0,
            transactionId = initData.stripeIntent.id
        )
    }

    private fun PaymentResult.convertToPaymentSheetResult() = when (this) {
        is PaymentResult.Completed -> PaymentSheetResult.Completed
        is PaymentResult.Canceled -> PaymentSheetResult.Canceled
        is PaymentResult.Failed -> PaymentSheetResult.Failed(throwable)
    }

    private fun LinkActivityResult.convertToPaymentResult() = when (this) {
        is LinkActivityResult.Completed -> PaymentResult.Completed
        is LinkActivityResult.Canceled -> PaymentResult.Canceled
        is LinkActivityResult.Failed -> PaymentResult.Failed(error)
    }

    class GooglePayException(
        val throwable: Throwable
    ) : Exception(throwable)

    @Parcelize
    data class Args(
        val clientSecret: String,
        val config: PaymentSheet.Configuration?
    ) : Parcelable

    companion object {
        fun getInstance(
            appContext: Context,
            viewModelStoreOwner: ViewModelStoreOwner,
            lifecycleScope: CoroutineScope,
            lifecycleOwner: LifecycleOwner,
            activityResultCaller: ActivityResultCaller,
            statusBarColor: () -> Int?,
            paymentOptionFactory: PaymentOptionFactory,
            paymentOptionCallback: PaymentOptionCallback,
            paymentResultCallback: PaymentSheetResultCallback
        ): PaymentSheet.FlowController {
            val injectorKey =
                WeakMapInjectorRegistry.nextKey(
                    requireNotNull(PaymentSheet.FlowController::class.simpleName)
                )
            val flowControllerComponent = DaggerFlowControllerComponent.builder()
                .appContext(appContext)
                .viewModelStoreOwner(viewModelStoreOwner)
                .lifecycleScope(lifecycleScope)
                .lifeCycleOwner(lifecycleOwner)
                .activityResultCaller(activityResultCaller)
                .statusBarColor(statusBarColor)
                .paymentOptionFactory(paymentOptionFactory)
                .paymentOptionCallback(paymentOptionCallback)
                .paymentResultCallback(paymentResultCallback)
                .injectorKey(injectorKey)
                .build()
            val flowController = flowControllerComponent.flowController
            flowController.flowControllerComponent = flowControllerComponent
            WeakMapInjectorRegistry.register(flowController, injectorKey)
            return flowController
        }
    }
}
