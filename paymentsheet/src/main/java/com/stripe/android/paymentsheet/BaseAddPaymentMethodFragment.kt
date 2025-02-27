package com.stripe.android.paymentsheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import com.stripe.android.core.injection.InjectorKey
import com.stripe.android.link.model.AccountStatus
import com.stripe.android.link.ui.inline.LinkInlineSignup
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.StripeIntent
import com.stripe.android.paymentsheet.databinding.FragmentPaymentsheetAddPaymentMethodBinding
import com.stripe.android.paymentsheet.model.PaymentSelection
import com.stripe.android.paymentsheet.model.getPMAddForm
import com.stripe.android.paymentsheet.paymentdatacollection.ComposeFormDataCollectionFragment
import com.stripe.android.paymentsheet.paymentdatacollection.FormFragmentArguments
import com.stripe.android.paymentsheet.paymentdatacollection.ach.USBankAccountFormFragment
import com.stripe.android.paymentsheet.ui.PrimaryButton
import com.stripe.android.paymentsheet.viewmodels.BaseSheetViewModel
import com.stripe.android.ui.core.Amount
import com.stripe.android.ui.core.PaymentsTheme
import com.stripe.android.ui.core.forms.resources.LpmRepository.SupportedPaymentMethod
import com.stripe.android.utils.AnimationConstants
import kotlinx.coroutines.flow.MutableStateFlow

internal abstract class BaseAddPaymentMethodFragment : Fragment() {
    abstract val viewModelFactory: ViewModelProvider.Factory
    abstract val sheetViewModel: BaseSheetViewModel<*>

    private lateinit var viewBinding: FragmentPaymentsheetAddPaymentMethodBinding
    private var showLinkInlineSignup = MutableStateFlow(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val themedInflater = inflater.cloneInContext(
            ContextThemeWrapper(requireActivity(), R.style.StripePaymentSheetAddPaymentMethodTheme)
        )
        return themedInflater.inflate(
            R.layout.fragment_paymentsheet_add_payment_method,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding = FragmentPaymentsheetAddPaymentMethodBinding.bind(view)

        val paymentMethods = sheetViewModel.supportedPaymentMethods

        sheetViewModel.headerText.value =
            getString(R.string.stripe_paymentsheet_add_payment_method_title)

        viewBinding.linkInlineSignup.apply {
            setContent {
                val processing by sheetViewModel.processing.observeAsState(false)

                PaymentsTheme {
                    LinkInlineSignup(
                        sheetViewModel.linkLauncher,
                        !processing
                    ) { viewState ->
                        sheetViewModel.updatePrimaryButtonUIState(
                            if (viewState.useLink) {
                                val userInput = viewState.userInput
                                if (userInput != null && sheetViewModel.selection.value != null) {
                                    PrimaryButton.UIState(
                                        label = null,
                                        onClick = { sheetViewModel.payWithLinkInline(userInput) },
                                        enabled = true,
                                        visible = true
                                    )
                                } else {
                                    PrimaryButton.UIState(
                                        label = null,
                                        onClick = null,
                                        enabled = false,
                                        visible = true
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }

        sheetViewModel.isResourceRepositoryReady.observe(viewLifecycleOwner) { isReady ->
            if (isReady == true) {
                val selectedPaymentMethodIndex = paymentMethods.indexOf(
                    sheetViewModel.addFragmentSelectedLPM
                ).takeUnless { it == -1 } ?: 0

                // In order to be able to read sheetViewModel.addFragmentSelectedLPM
                // the repository needs to be set. This might not be set if recovery
                // from a killed process or don't keep activities event
                if (paymentMethods.size > 1) {
                    setupRecyclerView(
                        viewBinding,
                        paymentMethods,
                        sheetViewModel.addFragmentSelectedLPM
                    )
                }

                if (paymentMethods.isNotEmpty()) {
                    updateLinkInlineSignupVisibility(paymentMethods[selectedPaymentMethodIndex])
                    // If the activity is destroyed and recreated, then the fragment is already present
                    // and doesn't need to be replaced, only the selected payment method needs to be set
                    if (savedInstanceState == null) {
                        replacePaymentMethodFragment(paymentMethods[selectedPaymentMethodIndex])
                    }
                }
            }
        }

        sheetViewModel.eventReporter.onShowNewPaymentOptionForm(
            linkEnabled = sheetViewModel.isLinkEnabled.value ?: false,
            activeLinkSession = sheetViewModel.activeLinkSession.value ?: false
        )
    }

    private fun setupRecyclerView(
        viewBinding: FragmentPaymentsheetAddPaymentMethodBinding,
        paymentMethods: List<SupportedPaymentMethod>,
        initialSelectedItem: SupportedPaymentMethod
    ) {
        viewBinding.paymentMethodsRecycler.isVisible = true
        viewBinding.paymentMethodsRecycler.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val processing by sheetViewModel.processing
                    .asFlow()
                    .collectAsState(initial = false)
                val selectedItem by sheetViewModel.getAddFragmentSelectedLpm()
                    .asFlow()
                    .collectAsState(initial = initialSelectedItem)
                PaymentMethodsUI(
                    selectedIndex = paymentMethods.indexOf(selectedItem),
                    isEnabled = !processing,
                    paymentMethods = paymentMethods,
                    onItemSelectedListener = { selectedLpm ->
                        if (sheetViewModel.addFragmentSelectedLPM != selectedLpm) {
                            onPaymentMethodSelected(selectedLpm)
                        }
                    }
                )
            }
        }
    }

    @VisibleForTesting
    internal fun onPaymentMethodSelected(paymentMethod: SupportedPaymentMethod) {
        // hide the soft keyboard.
        ViewCompat.getWindowInsetsController(requireView())
            ?.hide(WindowInsetsCompat.Type.ime())

        sheetViewModel.updatePrimaryButtonUIState(null)
        updateLinkInlineSignupVisibility(paymentMethod)
        replacePaymentMethodFragment(paymentMethod)
    }

    private fun replacePaymentMethodFragment(paymentMethod: SupportedPaymentMethod) {
        sheetViewModel.addFragmentSelectedLPM = paymentMethod

        val args = requireArguments()
        args.putParcelable(
            ComposeFormDataCollectionFragment.EXTRA_CONFIG,
            getFormArguments(
                stripeIntent = requireNotNull(sheetViewModel.stripeIntent.value),
                config = sheetViewModel.config,
                showPaymentMethod = paymentMethod,
                merchantName = sheetViewModel.merchantName,
                amount = sheetViewModel.amount.value,
                injectorKey = sheetViewModel.injectorKey,
                newLpm = sheetViewModel.newPaymentSelection,
                isShowingLinkInlineSignup = showLinkInlineSignup.value
            )
        )

        childFragmentManager.commit {
            setCustomAnimations(
                AnimationConstants.FADE_IN,
                AnimationConstants.FADE_OUT,
                AnimationConstants.FADE_IN,
                AnimationConstants.FADE_OUT
            )
            replace(
                R.id.payment_method_fragment_container,
                fragmentForPaymentMethod(paymentMethod),
                args
            )
        }
    }

    private fun updateLinkInlineSignupVisibility(selectedPaymentMethod: SupportedPaymentMethod) {
        showLinkInlineSignup.value = sheetViewModel.isLinkEnabled.value == true &&
            sheetViewModel.stripeIntent.value
                ?.linkFundingSources?.contains(PaymentMethod.Type.Card.code) ?: false &&
            selectedPaymentMethod.code == PaymentMethod.Type.Card.code &&
            sheetViewModel.linkLauncher.accountStatus.value == AccountStatus.SignedOut

        viewBinding.linkInlineSignup.isVisible = showLinkInlineSignup.value
    }

    private fun fragmentForPaymentMethod(paymentMethod: SupportedPaymentMethod) =
        when (paymentMethod.code) {
            PaymentMethod.Type.USBankAccount.code -> USBankAccountFormFragment::class.java
            else -> ComposeFormDataCollectionFragment::class.java
        }

    companion object {

        @VisibleForTesting
        fun getFormArguments(
            showPaymentMethod: SupportedPaymentMethod,
            stripeIntent: StripeIntent,
            config: PaymentSheet.Configuration?,
            merchantName: String,
            amount: Amount? = null,
            @InjectorKey injectorKey: String,
            newLpm: PaymentSelection.New?,
            isShowingLinkInlineSignup: Boolean = false
        ): FormFragmentArguments {
            val layoutFormDescriptor = showPaymentMethod.getPMAddForm(stripeIntent, config)

            return FormFragmentArguments(
                paymentMethodCode = showPaymentMethod.code,
                showCheckbox = layoutFormDescriptor.showCheckbox && !isShowingLinkInlineSignup,
                showCheckboxControlledFields = newLpm?.let {
                    newLpm.customerRequestedSave ==
                        PaymentSelection.CustomerRequestedSave.RequestReuse
                } ?: layoutFormDescriptor.showCheckboxControlledFields,
                merchantName = merchantName,
                amount = amount,
                billingDetails = config?.defaultBillingDetails,
                shippingDetails = config?.shippingDetails,
                injectorKey = injectorKey,
                initialPaymentMethodCreateParams =
                newLpm?.paymentMethodCreateParams?.typeCode?.takeIf {
                    it == showPaymentMethod.code
                }?.let {
                    when (newLpm) {
                        is PaymentSelection.New.GenericPaymentMethod ->
                            newLpm.paymentMethodCreateParams
                        is PaymentSelection.New.Card ->
                            newLpm.paymentMethodCreateParams
                        else -> null
                    }
                }
            )
        }
    }
}
