package akhil.alltrans

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

class ModelManagerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var modelManagerAdapter: ModelManagerAdapter
    private val modelManager = RemoteModelManager.getInstance()
    private val languageModelsList = mutableListOf<LanguageModelItem>()
    private lateinit var progressBar: ProgressBar
    private lateinit var infoTextView: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    // Handler para verificar status de download periodicamente
    private val downloadStatusHandler = Handler(Looper.getMainLooper())
    private val downloadCheckRunnable = object : Runnable {
        override fun run() {
            checkDownloadStatus()
            downloadStatusHandler.postDelayed(this, 3000) // Verifica a cada 3 segundos
        }
    }
    private var isCheckingDownloads = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_model_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.debugLog("ModelManagerFragment: onViewCreated")

        recyclerView = view.findViewById(R.id.models_recycler_view)
        progressBar = view.findViewById(R.id.model_manager_progress_bar)
        infoTextView = view.findViewById(R.id.info_textview)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)

        // Configurar SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener {
            Utils.debugLog("ModelManagerFragment: Swipe to refresh triggered.")
            loadLanguageModels()
        }

        // Configurar cores do SwipeRefreshLayout (opcional)
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )

        recyclerView.layoutManager = LinearLayoutManager(context)
        modelManagerAdapter = ModelManagerAdapter(
            languageModelsList,
            onDownloadClick = { modelItem ->
                handleDownloadModel(modelItem)
            },
            onDeleteClick = { modelItem ->
                handleDeleteModel(modelItem)
            }
        )
        recyclerView.adapter = modelManagerAdapter

        val dividerItemDecoration = DividerItemDecoration(
            recyclerView.context,
            (recyclerView.layoutManager as LinearLayoutManager).orientation
        )
        recyclerView.addItemDecoration(dividerItemDecoration)

        // Initial state setup before first load
        progressBar.visibility = View.VISIBLE
        infoTextView.text = getString(R.string.info_loading_models)
        infoTextView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        loadLanguageModels()
    }

    override fun onResume() {
        super.onResume()
        // Iniciar verificação de downloads quando o fragment estiver visível
        startDownloadStatusCheck()
    }

    override fun onPause() {
        super.onPause()
        // Parar verificação de downloads quando o fragment não estiver visível
        stopDownloadStatusCheck()
    }

    private fun startDownloadStatusCheck() {
        if (!isCheckingDownloads && hasDownloadingModels()) {
            isCheckingDownloads = true
            downloadStatusHandler.post(downloadCheckRunnable)
            Utils.debugLog("ModelManagerFragment: Started download status checking")
        }
    }

    private fun stopDownloadStatusCheck() {
        if (isCheckingDownloads) {
            isCheckingDownloads = false
            downloadStatusHandler.removeCallbacks(downloadCheckRunnable)
            Utils.debugLog("ModelManagerFragment: Stopped download status checking")
        }
    }

    private fun hasDownloadingModels(): Boolean {
        return languageModelsList.any { it.isDownloading }
    }

    private fun checkDownloadStatus() {
        if (!hasDownloadingModels()) {
            stopDownloadStatusCheck()
            return
        }

        Utils.debugLog("ModelManagerFragment: Checking download status...")

        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { downloadedModels: Set<TranslateRemoteModel> ->
                val downloadedLanguageCodes = downloadedModels.map { it.language }.toSet()
                var hasUpdates = false

                for (modelItem in languageModelsList) {
                    if (modelItem.isDownloading && downloadedLanguageCodes.contains(modelItem.languageCode)) {
                        // Model foi baixado com sucesso
                        modelItem.isDownloaded = true
                        modelItem.isDownloading = false
                        hasUpdates = true

                        Utils.debugLog("ModelManagerFragment: Model ${modelItem.languageCode} download completed")

                        // Mostrar toast de sucesso
                        Toast.makeText(
                            context,
                            getString(R.string.toast_model_downloaded_successfully, modelItem.displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                if (hasUpdates) {
                    modelManagerAdapter.notifyDataSetChanged()
                }

                // Se não há mais downloads em andamento, parar a verificação
                if (!hasDownloadingModels()) {
                    stopDownloadStatusCheck()
                }
            }
            .addOnFailureListener { e: Exception ->
                Log.e("ModelManagerFragment", "Failed to check download status", e)
            }
    }

    private fun loadLanguageModels() {
        Utils.debugLog("ModelManagerFragment: loadLanguageModels called. Is refreshing: ${swipeRefreshLayout.isRefreshing}")

        // Se não está sendo chamado pelo swipe refresh, mostrar progress bar central
        if (!swipeRefreshLayout.isRefreshing) {
            progressBar.visibility = View.VISIBLE
            infoTextView.text = getString(R.string.info_loading_models)
            infoTextView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }

        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { downloadedModels: Set<TranslateRemoteModel> ->
                Utils.debugLog("ModelManagerFragment: Successfully fetched ${downloadedModels.size} downloaded models.")
                val allLanguages = TranslateLanguage.getAllLanguages()
                val localLanguageModelsList = mutableListOf<LanguageModelItem>()

                val downloadedLanguageCodes = downloadedModels.map { it.language }.toSet()

                for (langCode in allLanguages) {
                    val displayName = TranslateLanguage.fromLanguageTag(langCode)?.let {
                        Locale(it).displayName
                    } ?: langCode
                    val isDownloaded = downloadedLanguageCodes.contains(langCode)

                    // Preservar o status de "downloading" se já estava baixando
                    val existingModel = languageModelsList.find { it.languageCode == langCode }
                    val isDownloading = if (isDownloaded) false else (existingModel?.isDownloading ?: false)

                    localLanguageModelsList.add(
                        LanguageModelItem(
                            languageCode = langCode,
                            displayName = displayName,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading
                        )
                    )
                }

                // Sort alphabetically by display name
                localLanguageModelsList.sortBy { it.displayName }

                // Update adapter on the main thread
                activity?.runOnUiThread {
                    if (localLanguageModelsList.isEmpty()) {
                        infoTextView.text = getString(R.string.info_no_models_available)
                        infoTextView.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        infoTextView.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                    languageModelsList.clear()
                    languageModelsList.addAll(localLanguageModelsList)
                    modelManagerAdapter.notifyDataSetChanged()

                    // Esconder loading indicators
                    progressBar.visibility = View.GONE
                    if (swipeRefreshLayout.isRefreshing) {
                        swipeRefreshLayout.isRefreshing = false
                    }

                    // Iniciar verificação de downloads se necessário
                    startDownloadStatusCheck()

                    Utils.debugLog("ModelManagerFragment: Updated adapter with ${languageModelsList.size} models.")
                }
            }
            .addOnFailureListener { e: Exception ->
                Log.e("ModelManagerFragment", "Failed to get downloaded models", e)
                activity?.runOnUiThread {
                    progressBar.visibility = View.GONE
                    recyclerView.visibility = View.GONE
                    infoTextView.visibility = View.VISIBLE
                    infoTextView.text = getString(R.string.info_error_loading_models)

                    // Parar o swipe refresh se estiver ativo
                    if (swipeRefreshLayout.isRefreshing) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                }
            }
    }

    private fun handleDownloadModel(modelItem: LanguageModelItem) {
        Utils.debugLog("ModelManagerFragment: handleDownloadModel for ${modelItem.languageCode}")

        // Update UI to show "downloading" state immediately
        modelItem.isDownloading = true
        modelItem.isDownloaded = false
        modelManagerAdapter.updateModelStatus(
            modelItem.languageCode,
            isDownloaded = false,
            isDownloading = true
        )

        // Iniciar verificação de status
        startDownloadStatusCheck()

        val model = TranslateRemoteModel.Builder(modelItem.languageCode).build()
        val conditions = DownloadConditions.Builder()
            .build()

        modelManager.download(model, conditions)
            .addOnSuccessListener {
                Utils.debugLog("ModelManagerFragment: Successfully started download for ${modelItem.languageCode}. Model will be available after download completes.")
                activity?.runOnUiThread {
                    Toast.makeText(
                        context,
                        getString(R.string.toast_download_started, modelItem.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .addOnFailureListener { e: Exception ->
                Log.e("ModelManagerFragment", "Failed to download model ${modelItem.languageCode}", e)
                activity?.runOnUiThread {
                    modelItem.isDownloading = false
                    modelManagerAdapter.updateModelStatus(
                        modelItem.languageCode,
                        isDownloaded = false,
                        isDownloading = false
                    )
                    Toast.makeText(
                        context,
                        getString(R.string.toast_failed_to_download_model, modelItem.displayName, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun handleDeleteModel(modelItem: LanguageModelItem) {
        Utils.debugLog("ModelManagerFragment: handleDeleteModel for ${modelItem.languageCode}")

        val model = TranslateRemoteModel.Builder(modelItem.languageCode).build()

        modelManager.deleteDownloadedModel(model)
            .addOnSuccessListener {
                Utils.debugLog("ModelManagerFragment: Successfully deleted model ${modelItem.languageCode}.")
                activity?.runOnUiThread {
                    modelItem.isDownloaded = false
                    modelItem.isDownloading = false
                    modelManagerAdapter.updateModelStatus(
                        modelItem.languageCode,
                        isDownloaded = false,
                        isDownloading = false
                    )
                    Toast.makeText(
                        context,
                        getString(R.string.toast_model_deleted_successfully, modelItem.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .addOnFailureListener { e: Exception ->
                Log.e("ModelManagerFragment", "Failed to delete model ${modelItem.languageCode}", e)
                activity?.runOnUiThread {
                    Toast.makeText(
                        context,
                        getString(R.string.toast_failed_to_delete_model, modelItem.displayName, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    // Método público para atualizar a lista (pode ser chamado de fora se necessário)
    fun refreshModels() {
        loadLanguageModels()
    }
}