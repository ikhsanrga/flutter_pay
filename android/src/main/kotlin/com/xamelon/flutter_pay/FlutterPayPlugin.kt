package com.xamelon.flutter_pay

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.annotation.NonNull;
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.util.ArrayUtils
import com.google.android.gms.wallet.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/** FlutterPayPlugin */
public class FlutterPayPlugin: FlutterPlugin, MethodCallHandler, PluginRegistry.ActivityResultListener, ActivityAware {

  private lateinit  var googlePayClient: PaymentsClient
  private lateinit var activity: Activity
  private var environment = WalletConstants.ENVIRONMENT_PRODUCTION

  private final val LOAD_PAYMENT_DATA_REQUEST_CODE = 991

  private var lastResult: Result? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    val channel = MethodChannel(flutterPluginBinding.getFlutterEngine().getDartExecutor(), "flutter_pay")
    channel.setMethodCallHandler(this)
  }

  private fun createPaymentsClient() {
    val walletOptions = Wallet.WalletOptions.Builder()
            .setEnvironment(environment)
            .setTheme(WalletConstants.THEME_LIGHT)
            .build()
    this.googlePayClient = Wallet.getPaymentsClient(this.activity, walletOptions)
  }

  companion object {
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      val channel = MethodChannel(registrar.messenger(), "flutter_pay")
      var plugin = FlutterPayPlugin()
      channel.setMethodCallHandler(plugin)
      registrar.addActivityResultListener(plugin)
      plugin.activity = registrar.activity()
      plugin.createPaymentsClient()
    }
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    this.lastResult = result
    if (call.method == "getPlatformVersion") {
      result.success("Android ${android.os.Build.VERSION.RELEASE}")
    } else if(call.method == "canMakePayments") {
      canMakePayments(result)
    } else if(call.method == "canMakePaymentsWithActiveCard") {
      val args = call.arguments as? Map<String, Any>
      if(args is Map<String, Any>) {
         canMakePaymentsWithActiveCard(args, result)
      }
    } else if(call.method == "requestPayment") {
      val args = call.arguments as? Map<String, Any>
      if(args is Map) {
        requestPayment(args)
      }
    } else if(call.method == "switchEnvironment") {
      val args = call.arguments as? Map<String, Any>
      if(args is Map<String, Any>) {
        switchEnvirnoment(args, result)
      }
    } else {
      result.notImplemented()
    }
  }

  private fun switchEnvirnoment(args: Map<String, Any>, result: Result) {
    val isTestEnvironment = args["isTestEnvironment"] as? Boolean
    if(isTestEnvironment != null) {
      if(isTestEnvironment) {
        environment = WalletConstants.ENVIRONMENT_TEST;
      } else {
        environment = WalletConstants.ENVIRONMENT_PRODUCTION;
      }
      createPaymentsClient()
    }
    result.success(true)
  }

  private fun getBaseRequest(): JSONObject {
    return JSONObject()
            .put("apiVersion", 2)
            .put("apiVersionMinor", 0)
  }

  private fun getGatewayJsonTokenizationType(gatewayName: String, gatewayMerchantID: String): JSONObject {
    return JSONObject().put("type", "PAYMENT_GATEWAY")
            .put("parameters", JSONObject()
                    .put("gateway", gatewayName)
                    .put("gatewayMerchantId", gatewayMerchantID))
  }

  private fun getAllowedCardSystems(): JSONArray {
    return JSONArray()
            .put("MASTERCARD")
            .put("VISA")
            .put("AMEX")
            .put("DISCOVER")
            .put("INTERAC")
            .put("JCB")
  }

  private fun getAllowedCardAuthMethods(): JSONArray {
    return JSONArray()
            .put("PAN_ONLY")
            .put("CRYPTOGRAM_3DS")
  }

  private fun getBaseCardPaymentMethod(allowedPaymentNetworks: List<String>? = null): JSONObject {
    val cardPaymentMethod = JSONObject().put("type","CARD");

    val cardNetworks: JSONArray
    if(allowedPaymentNetworks == null) {
      cardNetworks = getAllowedCardSystems()
    } else {
      cardNetworks = JSONArray(allowedPaymentNetworks)
    }

    val params = JSONObject()
            .put("allowedAuthMethods", getAllowedCardAuthMethods())
            .put("allowedCardNetworks", cardNetworks)

    cardPaymentMethod.put("parameters", params)
    return cardPaymentMethod
  }

  private fun getCardPaymentMethod(gatewayName: String, gatewayMerchantID: String, allowedPaymentNetworks: List<String>? = null): JSONObject {
    val cardPaymentMethod = getBaseCardPaymentMethod(allowedPaymentNetworks)
    val tokenizationOptions = getGatewayJsonTokenizationType(gatewayName, gatewayMerchantID)
    cardPaymentMethod.put("tokenizationSpecification", tokenizationOptions)
    return cardPaymentMethod
  }

  private fun getTransactionInfo(totalPrice: Double, currencyCode: String, countryCode: String): JSONObject {
    return JSONObject()
            .put("totalPrice", totalPrice.toInt().toString())
            .put("totalPriceStatus", "FINAL")
            .put("countryCode", countryCode)
            .put("currencyCode", currencyCode)
  }

  private fun requestPayment(args: Map<String, Any>) {
    val gatewayName = args["gatewayName"] as? String
    val gatewayMerchantID = args["gatewayMerchantId"] as? String
    val currencyCode = args["currencyCode"] as? String
    val countryCode = args["countryCode"] as? String
    val merchantName = args["merchantName"] as? String
    val items = args["items"] as? List<Map<String, String>>
    val allowedPaymentNetworks = args["allowedPaymentNetworks"] as List<String>

    var totalPrice: Double = 0.0
    if(items != null) {
      items.forEach {
        val price = it["price"]?.toDouble()
        if(price != null) {
          totalPrice += price
        }
      }
    }

    val paymentNetworks: List<String>
    if(allowedPaymentNetworks.count() > 0) {
      paymentNetworks = allowedPaymentNetworks.mapNotNull { decodePaymentNetwork(it) }
    } else {
      paymentNetworks = availablePaymentNetworks
    }

    if(totalPrice <= 0.0) {
      this.lastResult?.error("com.xammelon.flutterPay.zeroPrice", "Invalid price", "Total price cannot be zero or less than zero")
      return
    }
    if(gatewayName == null || gatewayMerchantID == null || currencyCode == null || countryCode == null) {
      this.lastResult?.error("com.xamelon.flutterPay.invalidParameters", "Invalid parameters", "Invalid parameters")
      return
    }

    var merchantInfo = JSONObject().putOpt("merchantName", merchantName)

    if(merchantInfo.length() == 0) merchantInfo = null

    val paymentRequestJson = getBaseRequest()
            .putOpt("merchantInfo", merchantInfo)
            .put("emailRequired", false)
            .put("transactionInfo", getTransactionInfo(totalPrice, currencyCode, countryCode))
            .put("allowedPaymentMethods", JSONArray().put(getCardPaymentMethod(gatewayName, gatewayMerchantID, paymentNetworks)))

    val paymentDataRequest = PaymentDataRequest.fromJson(paymentRequestJson.toString(4))
    var request = PaymentDataRequest.newBuilder()
            .setPhoneNumberRequired(false)
            .setEmailRequired(false)

    var paymentJson = paymentDataRequest.toJson()
    print("phone required: ${paymentDataRequest.isPhoneNumberRequired}\n")
    print("Payment data request: ${paymentDataRequest.toJson()}")

    if(paymentDataRequest != null) {
      val task = googlePayClient
              .loadPaymentData(paymentDataRequest)
              .addOnCompleteListener {
                try {
                  print("${it.getResult(ApiException::class.java)}")
                } catch(e: ApiException) {

                  print("Tortik:  e.message")
                }
              }
      AutoResolveHelper.resolveTask(task, this.activity, LOAD_PAYMENT_DATA_REQUEST_CODE)
    }

  }

  private fun canMakePayments(result: Result) {
    val baseRequest = getBaseRequest()
    baseRequest.put("allowedPaymentMethods", JSONArray().put(getBaseCardPaymentMethod()))

    val isReadyToPayRequest = IsReadyToPayRequest.fromJson(baseRequest.toString(4))

    val task = googlePayClient.isReadyToPay(isReadyToPayRequest)
    task.addOnCompleteListener {
      try {
        if(it.getResult(ApiException::class.java) == true) {
          result.success(true)
        } else {
          result.success(false)
        }
      } catch(e: ApiException) {
        e.printStackTrace()
        result.success(false)
      }
    }
  }

  private fun canMakePaymentsWithActiveCard(args: Map<String, Any>, result: Result) {
    val rawPaymentNetworks = args["paymentNetworks"] as? List<String>
    var paymentNetworks = rawPaymentNetworks?.mapNotNull { decodePaymentNetwork(it) }
    if(paymentNetworks?.count() == 0) {
      paymentNetworks = availablePaymentNetworks
    }
    val baseRequest = getBaseRequest();
    baseRequest.put("allowedPaymentMethods", JSONArray().put(getBaseCardPaymentMethod(paymentNetworks)))
    baseRequest.put("existingPaymentMethodRequired", true)

    val isReadyToPayRequest = IsReadyToPayRequest.fromJson(baseRequest.toString(4))

    val task = googlePayClient.isReadyToPay(isReadyToPayRequest)
    task.addOnCompleteListener {
      try {
        if(it.getResult(ApiException::class.java) == true) {
          result.success(true)
        } else {
          result.success(false)
        }
      } catch(e: ApiException) {
        e.printStackTrace()
        result.success(false)
      }
    }
  }


  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
  }


  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {

    if(requestCode == LOAD_PAYMENT_DATA_REQUEST_CODE) {
      print("Result code: $resultCode")
      if(resultCode == Activity.RESULT_OK) {
        if(data != null) {
          val paymentData = PaymentData.getFromIntent(data)
          print("Payment data: ${paymentData?.toJson()}")

          if(paymentData != null) {
            val paymentDataString = paymentData.toJson()
            val paymentDataJSONObject = JSONObject(paymentDataString)
            val paymentMethodData = paymentDataJSONObject["paymentMethodData"] as? JSONObject
            if(paymentMethodData != null) {
              val tokenizationData = paymentMethodData["tokenizationData"] as? JSONObject
              if (tokenizationData != null) {
                val token = tokenizationData["token"] as? String
                if (token != null) {
                  val response: Map<String, String> = mapOf("token" to token, "error" to "")
                  this.lastResult?.success(response)
                }
              }
            }
          }

        }

      } else if(resultCode == Activity.RESULT_CANCELED) {
        this.lastResult?.error("com.flutter_pay.userCancelledError", "User cancelled the payment", null);
      }

      this.lastResult = null
    }
    return false
  }


  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.activity = binding.activity
    binding.addActivityResultListener(this)
    createPaymentsClient()
  }

  override fun onDetachedFromActivity() {

  }

  override fun onDetachedFromActivityForConfigChanges() {

  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    this.activity = binding.activity
    binding.addActivityResultListener(this)
    createPaymentsClient()
  }
}
