package akhil.alltrans

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Filter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

class AppListFragment : Fragment(), SearchableFragment {

    private var listview: ListView? = null
    private var loadingIndicator: CircularProgressIndicator? = null
    private var adapter: StableArrayAdapter? = null
    // currentAppliedFilterQuery não é mais necessário aqui, o adapter será filtrado pelo observador da ViewModel

    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // O estado do filtro é gerenciado pela MainViewModel agora
        Utils.debugLog("AppListFragment: onCreateView")
        return inflater.inflate(R.layout.apps_list, container, false)
    }

    // onSaveInstanceState não é mais necessário para a query aqui

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.debugLog("AppListFragment: onViewCreated. Initial ViewModel query: '${mainViewModel.activeSearchQuery.value}'")
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        listview = view.findViewById(R.id.AppsList)

        settings = requireActivity().getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)

        listview?.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        listview?.isFastScrollEnabled = true
        listview?.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val curApp = adapter?.getItem(position)
            if (curApp != null) {
                (activity as? MainActivity)?.searchView?.clearFocus()
                val localPreferenceFragment = LocalPreferenceFragment()
                localPreferenceFragment.applicationInfo = curApp
                parentFragmentManager.beginTransaction()
                    .replace(R.id.toReplace, localPreferenceFragment)
                    .addToBackStack(null)
                    .commit()
            } else {
                Log.e("AppListFragment", "Clicked item at position $position is null in adapter.")
            }
        }

        // Observar a query de pesquisa da ViewModel para filtrar a lista
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.activeSearchQuery.collectLatest { query ->
                Utils.debugLog("AppListFragment: Observed query from ViewModel: '$query'. Applying to adapter.")
                // Aplica o filtro ao adapter. Se o adapter ainda não estiver pronto,
                // loadPackagesAndSetupAdapter cuidará de aplicar o filtro inicial.
                adapter?.filter?.filter(query)
            }
        }
        // Carrega os pacotes. O filtro será aplicado pelo observador acima ou em loadPackagesAndSetupAdapter.
        loadPackagesAndSetupAdapter()
    }

    override fun onResume() {
        super.onResume()
        Utils.debugLog("AppListFragment: onResume.")
        // Garante que a MainActivity saiba que este fragmento está ativo para mostrar o menu de pesquisa
        mainViewModel.updateCurrentFragmentType(MainViewModel.FragmentType.APPS)
        // Se os dados precisarem ser recarregados CADA VEZ que o fragmento é resumido (ex: para pegar apps recém-instalados),
        // descomente a linha abaixo. Caso contrário, carregar em onViewCreated pode ser suficiente.
        // loadPackagesAndSetupAdapter() // Decida se isso é necessário aqui ou apenas em onViewCreated
    }

    // Chamado pela MainActivity (através da interface SearchableFragment)
    // Esta implementação é redundante se o fragmento já observa a ViewModel.
    // A Activity atualiza a ViewModel, e o Fragment reage à ViewModel.
    override fun updateSearchQuery(query: String?) {
        Utils.debugLog("AppListFragment: updateSearchQuery (from SearchableFragment interface) called with: '$query'. ViewModel's query is '${mainViewModel.activeSearchQuery.value}'")
        // A ViewModel é a fonte da verdade. Se a Activity chamar isso,
        // é porque a ViewModel já foi (ou está sendo) atualizada.
        // O observador de activeSearchQuery no fragmento deve lidar com isso.
        // Para garantir uma resposta imediata, podemos filtrar aqui também,
        // mas pode causar uma dupla filtragem se o collectLatest também disparar.
        // if (adapter?.currentQuery != query) { // Precisaria de uma forma de saber a query atual do adapter
        //    adapter?.filter?.filter(query)
        // }
    }

    private fun loadPackagesAndSetupAdapter() {
        val initialQueryFromViewModel = mainViewModel.activeSearchQuery.value
        Utils.debugLog("AppListFragment: loadPackagesAndSetupAdapter. Initial filter from ViewModel: '$initialQueryFromViewModel'")
        loadingIndicator?.isVisible = true
        listview?.isVisible = false

        lifecycleScope.launch(Dispatchers.Main) {
            val freshLoadedPackages = withContext(Dispatchers.IO) {
                Utils.debugLog("AppListFragment: Loading packages in IO thread...")
                val pm = requireContext().packageManager
                var packages = getInstalledApplications(requireContext())
                if (Utils.isExpModuleActive(requireContext())) {
                    val packageNames = Utils.getExpApps(requireContext())
                    packages = packages.filter { appInfo -> packageNames.contains(appInfo.packageName) }.toMutableList()
                }
                packages.sortWith(Comparator { a, b ->
                    val aEnabled = settings?.contains(a.packageName) == true
                    val bEnabled = settings?.contains(b.packageName) == true
                    if (aEnabled && !bEnabled) return@Comparator -1
                    if (!aEnabled && bEnabled) return@Comparator 1
                    val labelA = pm.getApplicationLabel(a).toString().lowercase(Locale.getDefault())
                    val labelB = pm.getApplicationLabel(b).toString().lowercase(Locale.getDefault())
                    labelA.compareTo(labelB)
                })
                Utils.debugLog("AppListFragment: Loaded ${packages.size} packages in IO thread.")
                packages
            }

            if (isAdded) {
                Utils.debugLog("AppListFragment: Packages loaded, creating/updating adapter with ${freshLoadedPackages.size} items.")
                // Sempre criar um novo adapter ao carregar os pacotes garante que
                // originalValues no adapter esteja sempre atualizado.
                adapter = StableArrayAdapter(requireContext(), freshLoadedPackages)
                listview?.adapter = adapter

                // Aplica a query inicial da ViewModel após o adapter ser configurado
                Utils.debugLog("AppListFragment: Applying initial ViewModel filter '$initialQueryFromViewModel' after new adapter set.")
                adapter?.filter?.filter(initialQueryFromViewModel)

                listview?.isVisible = true
            }
            loadingIndicator?.isVisible = false
            Utils.debugLog("AppListFragment: loadPackagesAndSetupAdapter finished.")
        }
    }

    internal class ViewHolder {
        var textView: TextView? = null
        var textView2: TextView? = null
        var imageView: ImageView? = null
        var checkBox: CheckBox? = null
    }

    private inner class StableArrayAdapter(
        context: Context,
        initialPackages: List<ApplicationInfo>
    ) : ArrayAdapter<ApplicationInfo>(context, R.layout.list_item, initialPackages) {

        private var originalValues: List<ApplicationInfo> = ArrayList(initialPackages)
        private val pm: PackageManager = context.packageManager
        private val inflater: LayoutInflater = LayoutInflater.from(context)

        // Este método agora é o principal para atualizar os dados base do adapter
        // e refiltrar com a query fornecida (que virá da ViewModel).
        fun updateOriginalDataAndFilter(newPackages: List<ApplicationInfo>, currentQuery: String?) {
            Utils.debugLog("StableArrayAdapter: updateOriginalDataAndFilter with ${newPackages.size} packages. Query: '$currentQuery'")
            this.originalValues = ArrayList(newPackages)
            this.filter.filter(currentQuery) // Aplica o filtro sobre os novos dados base
        }

        override fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    Utils.debugLog("StableArrayAdapter: performFiltering constraint: '$constraint', originalValues size: ${originalValues.size}")
                    val results = FilterResults()
                    val valuesToFilterFrom = ArrayList(originalValues)
                    val filterString = constraint?.toString()?.lowercase(Locale.getDefault())

                    if (filterString.isNullOrEmpty()) {
                        results.values = valuesToFilterFrom
                        results.count = valuesToFilterFrom.size
                    } else {
                        val filteredList = ArrayList<ApplicationInfo>()
                        for (appInfo in valuesToFilterFrom) {
                            val appName = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
                            val packageName = appInfo.packageName.lowercase(Locale.getDefault())
                            if (appName.contains(filterString) || packageName.contains(filterString)) {
                                filteredList.add(appInfo)
                            }
                        }
                        results.values = filteredList
                        results.count = filteredList.size
                    }
                    Utils.debugLog("StableArrayAdapter: performFiltering results count: ${results.count}")
                    return results
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    clear()
                    if (results?.values != null) {
                        try {
                            addAll(results.values as List<ApplicationInfo>)
                        } catch (e: ClassCastException) {
                            Log.e("AppListFragment", "Error casting filter results", e)
                            if (constraint.isNullOrEmpty()) addAll(ArrayList(originalValues))
                        }
                    } else if (constraint.isNullOrEmpty()) {
                        addAll(ArrayList(originalValues))
                    }
                    notifyDataSetChanged()
                    Utils.debugLog("StableArrayAdapter: publishResults finished. Adapter count: $count")
                }
            }
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val viewHolder: ViewHolder

            if (convertView == null) {
                view = inflater.inflate(R.layout.list_item, parent, false)
                viewHolder = ViewHolder()
                viewHolder.textView = view.findViewById(R.id.firstLine)
                viewHolder.textView2 = view.findViewById(R.id.secondLine)
                viewHolder.imageView = view.findViewById(R.id.icon)
                viewHolder.checkBox = view.findViewById(R.id.checkBox)
                view.tag = viewHolder
            } else {
                view = convertView
                viewHolder = view.tag as ViewHolder
            }

            val currentAppInfo = getItem(position)

            if (currentAppInfo != null) {
                val packageName = currentAppInfo.packageName ?: ""
                val label = pm.getApplicationLabel(currentAppInfo).toString()
                val icon = pm.getApplicationIcon(currentAppInfo)

                viewHolder.textView?.text = label
                viewHolder.textView?.isSelected = true
                viewHolder.textView2?.text = packageName
                viewHolder.textView2?.isSelected = true
                viewHolder.imageView?.setImageDrawable(icon)

                viewHolder.checkBox?.tag = currentAppInfo
                viewHolder.checkBox?.isChecked = settings?.contains(packageName) == true
                viewHolder.checkBox?.setOnClickListener { v ->
                    val checkBox = v as CheckBox
                    val appInfoFromTag = checkBox.tag as? ApplicationInfo ?: return@setOnClickListener
                    val pkgName = appInfoFromTag.packageName ?: return@setOnClickListener
                    Utils.debugLog("CheckBox clicked! $pkgName")

                    val globalEditor = settings?.edit()
                    val localSettings = requireActivity().getSharedPreferences(pkgName, Context.MODE_PRIVATE)
                    val localEditor = localSettings?.edit()

                    if (checkBox.isChecked) {
                        globalEditor?.putBoolean(pkgName, true)
                        localEditor?.putBoolean("LocalEnabled", true)
                    } else {
                        globalEditor?.remove(pkgName)
                        localEditor?.putBoolean("LocalEnabled", false)
                    }
                    globalEditor?.apply()
                    localEditor?.apply()
                }
            } else {
                viewHolder.textView?.text = ""
                viewHolder.textView2?.text = ""
                viewHolder.imageView?.setImageDrawable(null)
                viewHolder.checkBox?.setOnClickListener(null)
                viewHolder.checkBox?.tag = null
                viewHolder.checkBox?.isChecked = false
            }
            return view
        }
    }

    private fun getInstalledApplications(context: Context): MutableList<ApplicationInfo> {
        val pm = context.packageManager
        try {
            return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w("AppListFragment", "getInstalledApplications (modern) failed, using fallback.", e)
        }
        val result: MutableList<ApplicationInfo> = ArrayList()
        var bufferedReader: BufferedReader? = null
        try {
            val process = Runtime.getRuntime().exec("pm list packages")
            bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (bufferedReader.readLine().also { line = it } != null) {
                val packageName = line?.substringAfter("package:", "")?.trim()
                if (!packageName.isNullOrEmpty()) {
                    try {
                        val applicationInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                        result.add(applicationInfo)
                    } catch (e: PackageManager.NameNotFoundException) { /* Ignorar */ }
                    catch (e: Exception) {
                        Log.e("AppListFragment", "Error getting app info during fallback for $packageName", e)
                    }
                }
            }
            process.waitFor()
        } catch (e: Throwable) {
            Log.e("AppListFragment", "Error during fallback getInstalledApplications with 'pm list packages'", e)
        } finally {
            try { bufferedReader?.close() } catch (e: IOException) { /* Ignorar */ }
        }
        Log.i("AppListFragment", "Fallback 'pm list packages' loaded ${result.size} packages.")
        return result
    }

    companion object {
        private var settings: SharedPreferences? = null
    }
}