package com.example.gameoflife

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.dhaval2404.colorpicker.ColorPickerDialog
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import kotlin.properties.Delegates

class GameFragment : Fragment() {
    private var isRunning = false
    private val MIN_GRID_SIZE = 10
    private val MAX_GENERATIONS = 10

    private val GRID_SIZE_KEY = "GRID_SIZE"
    private val CELL_STATUSES_KEY = "CELL_STATUSES"
    private val GENERATION_KEY = "GENERATION"
    private val DELAY_KEY = "DELAY"
    private val ALIVE_COLOR_KEY = "ALIVE_COLOR"
    private val DEAD_COLOR_KEY = "DEAD_COLOR"

    private val OPEN_FILE_REQUEST_CODE = 123
    private val fileNameDateFormat = SimpleDateFormat("yyyy-mm-dd--hh-mm-ss")
    private lateinit var savedGridsDirectory: File

    private var gridSize by Delegates.notNull<Int>()
    private lateinit var cellStatuses: Array<Array<Int>>
    private var generation by Delegates.notNull<Int>()
    private var delay by Delegates.notNull<Int>()
    private var aliveColor by Delegates.notNull<Int>()
    private var deadColor by Delegates.notNull<Int>()

    private lateinit var recyclerView: RecyclerView
    private lateinit var generationNumTextView: TextView
    private lateinit var gridSizeTextView: TextView
    private lateinit var gridSizeSeekBar: SeekBar
    private lateinit var delayTextView: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedGridsDirectory = File(context?.getExternalFilesDir(null), getString(R.string.saved_grids_directory))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_game, container, false)

        // Intent Extras
        gridSize = activity?.intent?.getIntExtra(GRID_SIZE_KEY, 20) ?: 20
        cellStatuses = (activity?.intent?.extras?.get(CELL_STATUSES_KEY) ?: Array(gridSize) { Array(gridSize) { 0 } }) as Array<Array<Int>>
        generation = activity?.intent?.getIntExtra(GENERATION_KEY, 0) ?: 0
        delay = activity?.intent?.getIntExtra(DELAY_KEY, 500) ?: 500
        aliveColor = activity?.intent?.getIntExtra(ALIVE_COLOR_KEY, Color.BLACK) ?: Color.BLACK
        deadColor = activity?.intent?.getIntExtra(DEAD_COLOR_KEY, Color.WHITE) ?: Color.WHITE

        // Recycler View
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = GridLayoutManager(context, gridSize)
        recyclerView.adapter = GameOfLifeAdapter()

        // Generation Text
        generationNumTextView = view.findViewById(R.id.generation_num_text)
        generationNumTextView.text = getString(R.string.generation_num_text, generation)

        // Grid Size Text
        gridSizeTextView = view.findViewById(R.id.grid_size_text)
        gridSizeTextView.text = getString(R.string.grid_size_text, gridSize)

        // Grid Size Seek Bar
        gridSizeSeekBar = view.findViewById<SeekBar>(R.id.grid_size_seek_bar)
        gridSizeSeekBar.apply {
            progress = gridSize - MIN_GRID_SIZE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    stopGame()
                    gridSize = progress + MIN_GRID_SIZE
                    gridSizeTextView.text = getString(R.string.grid_size_text, gridSize)
                    cellStatuses = convertToNewGridSize()
                    recyclerView.layoutManager = GridLayoutManager(context, gridSize)
                    recyclerView.adapter = GameOfLifeAdapter()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        // Delay Text
        delayTextView = view.findViewById(R.id.delay_text)
        delayTextView.text = getString(R.string.delay_text, delay)

        // Delay Seek Bar
        view.findViewById<SeekBar>(R.id.delay_seek_bar).apply {
            progress = delay
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    delay = progress
                    delayTextView.text = getString(R.string.delay_text, progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        // Start Button
        startButton = view.findViewById(R.id.start_btn)
        startButton.setOnClickListener { if (!isRunning && !isGridEmpty()) { startGame() } else { stopGame() } }

        // Reset Button
        view.findViewById<Button>(R.id.reset_btn).setOnClickListener {
            stopGame()
            activity?.recreate()
        }

        // Share Button
        view.findViewById<Button>(R.id.share_btn).setOnClickListener {
            stopGame()

            // Credit: https://stackoverflow.com/a/30172792
            val bitmap: Bitmap = takeScreenshot(recyclerView)
            try {
                val cachePath = File(requireContext().cacheDir, "images")
                cachePath.mkdirs()
                val outputStream = FileOutputStream("$cachePath/image.png")
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            val imagePath = File(requireContext().cacheDir, "images")
            val imageFile = File(imagePath, "image.png")
            val contentUri = FileProvider.getUriForFile(requireContext(), "com.example.gameoflife.fileprovider", imageFile)
            if (contentUri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setDataAndType(contentUri, requireActivity().contentResolver.getType(contentUri))
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_btn)))
            }
        }

        // Pick Alive Color Button
        view.findViewById<Button>(R.id.alive_color_btn).setOnClickListener {
            ColorPickerDialog
                .Builder(requireActivity())
                .setDefaultColor(aliveColor)
                .setColorListener { color, _ ->
                    aliveColor = color
                    (recyclerView.adapter as GameOfLifeAdapter).updateColor()
                }
                .show()
        }

        // Pick Dead Color Button
        view.findViewById<Button>(R.id.dead_color_btn).setOnClickListener {
            ColorPickerDialog
                .Builder(requireActivity())
                .setDefaultColor(deadColor)
                .setColorListener { color, _ ->
                    deadColor = color
                    (recyclerView.adapter as GameOfLifeAdapter).updateColor()
                }
                .show()
        }

        // Save Button
        view.findViewById<Button>(R.id.save_btn).setOnClickListener {
            stopGame()
            if (!savedGridsDirectory.exists()) { savedGridsDirectory.mkdirs() }
            val file = File(savedGridsDirectory, getString(R.string.saved_grid_file_name, fileNameDateFormat.format(Date())))
            for (i in cellStatuses.indices) {
                val row = StringBuilder()
                for (j in cellStatuses[i].indices) {
                    row.append("${minOf(cellStatuses[i][j], 1)},")
                }
                file.appendText("$row\n")
            }
            Toast.makeText(context, getString(R.string.grid_saved_toast), Toast.LENGTH_SHORT).show()
        }

        // Open Button
        view.findViewById<Button>(R.id.open_btn).setOnClickListener {
            stopGame()

            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/comma-separated-values"
            }
            startActivityForResult(Intent.createChooser(intent, getString(R.string.open_btn)), OPEN_FILE_REQUEST_CODE)
        }

        // Clone Button
        view.findViewById<Button>(R.id.clone_btn).setOnClickListener {
            stopGame()

            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(GRID_SIZE_KEY, gridSize)
                putExtra(CELL_STATUSES_KEY, cellStatuses)
                putExtra(GENERATION_KEY, generation)
                putExtra(DELAY_KEY, delay)
                putExtra(ALIVE_COLOR_KEY, aliveColor)
                putExtra(DEAD_COLOR_KEY, deadColor)
            }
            startActivity(intent)
        }

        return view
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OPEN_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            val newCellStatuses: MutableList<Array<Int>> = mutableListOf()
            context?.contentResolver?.openInputStream(data?.data!!)?.bufferedReader()?.forEachLine { line ->
                newCellStatuses.add(line.split(',').dropLast(1).map { it.toInt() }.toTypedArray())
            }
            cellStatuses = newCellStatuses.toTypedArray()
            updateGeneration(0)

            if (cellStatuses.size != gridSize) {
                gridSize = cellStatuses.size
                gridSizeTextView.text = getString(R.string.grid_size_text, gridSize)
                gridSizeSeekBar.progress = gridSize
                recyclerView.layoutManager = GridLayoutManager(context, gridSize)
                recyclerView.adapter = GameOfLifeAdapter()
            } else {
                (recyclerView.adapter as GameOfLifeAdapter).updateState()
            }
        }
    }

    private fun startGame() {
        isRunning = true
        startButton.text = getString(R.string.stop_btn)

        CoroutineScope(Dispatchers.IO).launch {
            while (isRunning) {
                val updatedCellStatuses = Array(gridSize) { Array(gridSize) { 0 } }
                for (row in 0 until gridSize) {
                    for (column in 0 until gridSize) {
                        val livingNeighborsCount = getLivingNeighborsCount(row, column)
                        if ((livingNeighborsCount == 2 && cellStatuses[row][column] > 0) || livingNeighborsCount == 3) {
                            updatedCellStatuses[row][column] = (cellStatuses[row][column] + 1) % MAX_GENERATIONS
                        }
                    }
                }
                cellStatuses = updatedCellStatuses

                // update views
                withContext(Dispatchers.Main) {
                    (recyclerView.adapter as GameOfLifeAdapter).updateState()
                    updateGeneration(generation + 1)
                    if (isGridEmpty()) { stopGame() }
                }

                delay(delay.toLong())
            }
        }
    }

    private fun stopGame() {
        isRunning = false
        startButton.text = getString(R.string.start_btn)
    }

    private fun isGridEmpty(): Boolean {
        for (row in cellStatuses.indices) {
            for (column in cellStatuses[row].indices) {
                if (cellStatuses[row][column] > 0) { return false }
            }
        }
        return true
    }

    private fun getLivingNeighborsCount(row: Int, column: Int): Int {
        val previousRow = (row + gridSize - 1) % gridSize
        val nextRow = (row + 1) % gridSize
        val previousColumn = (column + gridSize - 1) % gridSize
        val nextColumn = (column + 1) % gridSize

        return minOf(cellStatuses[previousRow][previousColumn], 1) +
                minOf(cellStatuses[previousRow][column], 1) +
                minOf(cellStatuses[previousRow][nextColumn], 1) +
                minOf(cellStatuses[row][nextColumn], 1) +
                minOf(cellStatuses[nextRow][nextColumn], 1) +
                minOf(cellStatuses[nextRow][column], 1) +
                minOf(cellStatuses[nextRow][previousColumn], 1) +
                minOf(cellStatuses[row][previousColumn], 1)
    }

    private fun updateGeneration(value: Int) {
        generation = value
        generationNumTextView.text = getString(R.string.generation_num_text, value)
    }

    private fun reduceCellsToOneGeneration() {
        for (row in cellStatuses.indices) {
            for (column in cellStatuses[row].indices) {
                cellStatuses[row][column] = minOf(cellStatuses[row][column], 1)
            }
        }
    }

    private fun convertToNewGridSize(): Array<Array<Int>> {
        val minGridSize = minOf(cellStatuses.size, gridSize)
        val maxGridSize = maxOf(cellStatuses.size, gridSize)

        val newGrid = Array(gridSize) { Array(gridSize) { 0 } }
        for (row in 0 until maxGridSize) {
            for (column in 0 until maxGridSize) {
                if (row < minGridSize && column < minGridSize) {
                    newGrid[row][column] = cellStatuses[row][column]
                }
            }
        }

        return newGrid
    }

    private fun takeScreenshot(view: View): Bitmap {
        view.isDrawingCacheEnabled = true
        view.buildDrawingCache(true)
        val bitmap = Bitmap.createBitmap(view.drawingCache)
        view.isDrawingCacheEnabled = false
        return bitmap
    }

    private inner class CellViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.container)
        private val imageView: ImageView = itemView.findViewById(R.id.cell_btn)
        private var row by Delegates.notNull<Int>()
        private var column by Delegates.notNull<Int>()

        init {
            imageView.setOnClickListener {
                if (!isRunning) {
                    updateGeneration(0)
                    reduceCellsToOneGeneration()
                    cellStatuses[row][column] = (cellStatuses[row][column] + 1) % 2
                    updateState()
                }
            }
        }

        fun bind(position: Int) {
            row = position / gridSize
            column = position % gridSize
        }

        fun updateState() {
            if (cellStatuses[row][column] > 0) {
                imageView.apply {
                    setImageResource(R.drawable.person)
                    if (animation == null) {
                        startAnimation(AnimationUtils.loadAnimation(context, R.anim.fade))
                    }
                }
            } else {
                imageView.apply {
                    setImageResource(ResourcesCompat.ID_NULL)
                    clearAnimation()
                }
            }
        }

        fun updateColor() {
            container.setBackgroundColor(deadColor)
            imageView.setColorFilter(aliveColor)
        }
    }

    private inner class GameOfLifeAdapter: RecyclerView.Adapter<CellViewHolder>() {
        private val viewHolders = mutableListOf<CellViewHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellViewHolder {
            val view = layoutInflater.inflate(R.layout.item_cell, parent, false)
            val marginPx = (2 * gridSize + 10) * requireContext().resources.displayMetrics.density
            view.layoutParams.apply {
                width = (parent.measuredWidth - marginPx).div(gridSize).roundToInt()
                height = width
            }
            return CellViewHolder(view)
        }

        override fun onBindViewHolder(holder: CellViewHolder, position: Int) {
            holder.apply {
                bind(position)
                updateState()
                updateColor()
            }
            viewHolders.add(holder)
        }

        override fun getItemCount(): Int = gridSize * gridSize
        fun updateState() { viewHolders.forEach { it.updateState() } }
        fun updateColor() { viewHolders.forEach { it.updateColor() } }
    }
}