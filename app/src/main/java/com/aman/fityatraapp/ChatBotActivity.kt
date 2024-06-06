package com.aman.fityatraapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aman.fityatraapp.models.DietPlan
import com.aman.fityatraapp.models.ExerciseAdd
import com.aman.fityatraapp.models.MealAdd
import com.aman.fityatraapp.models.UserHealthData
import com.aman.fityatraapp.utils.ApiClient.apiService
import com.aman.fityatraapp.utils.ExerciseAddAdapter
import com.aman.fityatraapp.utils.FirebaseUtils
import com.aman.fityatraapp.utils.Item
import com.aman.fityatraapp.utils.MealAddAdapter
import com.aman.fityatraapp.utils.PermissionManager
import com.aman.fityatraapp.utils.exerItem
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
//import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.InputStreamReader


data class Question(
    val question: String,
    val type: String,
    val options: List<String>? = null
)

class ChatBotActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var chatLayout: LinearLayout
    private lateinit var inputLayout: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var scrollView: ScrollView
    private var currentQuestionIndex = 0
    private val questions = mutableListOf<Question>()
    private val userData = mutableMapOf<String, String>()
    private val firebaseUtils = FirebaseUtils()
    private val permissionManager = PermissionManager()
    private var isQuestionsAsked = false
    private var mealList = mutableListOf<MealAdd>()
    private var exerciseList = mutableListOf<ExerciseAdd>()
    private lateinit var mealAddAdapter: MealAddAdapter
    private lateinit var exerciseAddAdapter: ExerciseAddAdapter



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_bot)

        supportActionBar?.hide()

        chatLayout = findViewById(R.id.chatLayout)
        inputLayout = findViewById(R.id.inputLayout)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        scrollView = findViewById(R.id.scrollView)

        auth = FirebaseAuth.getInstance()

        loadQuestions()
        checkUserLogin()

        sendButton.setOnClickListener {
            handleSendButtonClick()
        }
        permissionManager.initPermissionLauncher(this)
    }

    private fun loadQuestions() {
        val inputStream = assets.open("questions.json")
        val reader = InputStreamReader(inputStream)
        val gson = Gson()
        val questionsArray = gson.fromJson(reader, Array<Question>::class.java)
        questions.addAll(questionsArray)
    }

    private fun checkUserLogin() {
        if (auth.currentUser == null) {
            showLoginPrompt()
        } else {
            greetUserAndCheckData()
        }
    }

    private fun showLoginPrompt() {
        val signInButton = Button(this).apply {
            id = R.id.sign_in_button
            text = "Sign In with Google"
            setOnClickListener { signIn() }
        }
        chatLayout.addView(signInButton)
        addChatMessage("Please login first.")
    }

    private fun signIn() {
        val signInIntent = firebaseUtils.getGoogleSignInClient(this).signInIntent
        startActivityForResult(signInIntent, FirebaseUtils.RC_SIGN_IN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FirebaseUtils.RC_SIGN_IN) {
            val signInAccountTask: Task<GoogleSignInAccount> =
                GoogleSignIn.getSignedInAccountFromIntent(data)

            // check condition
            if (signInAccountTask.isSuccessful) {
                try {
                    val googleSignInAccount = signInAccountTask.getResult(ApiException::class.java)

                    if (googleSignInAccount != null) {
                        val authCredential: AuthCredential = GoogleAuthProvider.getCredential(
                            googleSignInAccount.idToken, null
                        )
                        firebaseUtils.auth.signInWithCredential(authCredential)
                            .addOnCompleteListener(this) { task ->
                                if (task.isSuccessful) {
                                    removeSignInButton()
                                    checkUserData()
                                } else {

                                }
                            }
                    }
                } catch (e: ApiException) {
                    e.printStackTrace()
                }
            }
        }
    }
    private fun removeSignInButton() {
        val signInButton = chatLayout.findViewById<Button>(R.id.sign_in_button)
        chatLayout.removeView(signInButton)
    }


    private fun greetUserAndCheckData() {
        val user = auth.currentUser
        if (user != null) {
            val displayName = user.displayName ?: "User"
            addChatMessage("Hello, $displayName!")
            checkUserData()
        }
    }


    private fun checkUserData() {
        val user = auth.currentUser
        if (user != null) {
            firebaseUtils.getUserData(user.uid).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val snapshot = task.result
                    if (snapshot != null && snapshot.exists()) {
                        isQuestionsAsked = true
                        setupChatBot(snapshot)
                    } else {
                        askNextQuestion()
                    }
                } else {
                    addChatMessage("Error retrieving user data.")
                }
            }
        }
    }
    private fun setupChatBot(snapshot: DataSnapshot) {
        addChatMessage("Welcome To the App! How can I assist you with your health?")
    }


    private fun askNextQuestion() {
        if (currentQuestionIndex < questions.size) {
            val question = questions[currentQuestionIndex]
            addChatMessage(question.question)
            when (question.type) {
                "number" -> showInputField(InputType.TYPE_CLASS_NUMBER)
                "height" -> askHeightPreference()
                "dropdown" -> showOptionButtons(question.options!!)
                "text" -> showInputField(InputType.TYPE_CLASS_TEXT)
            }
        } else {
            saveUserData()
            checkUserData()
        }
    }

    private fun handleSendButtonClick() {
        val input = inputField.text.toString()
        if(isQuestionsAsked){
            handleUserInput(input)
            addChatMessage(input, true)
            inputField.text.clear()
            return
        }
        if (input.isNotEmpty()) {
            userData[questions[currentQuestionIndex].question] = input
            addChatMessage(input, true)
            inputField.text.clear()
            currentQuestionIndex++

            if(!isQuestionsAsked){
                askNextQuestion()
            }else{

            }

        } else {
            addChatMessage("Please provide a valid answer.")
        }
    }

    private fun showInputField(inputType: Int) {
        inputField.inputType = inputType
        inputLayout.visibility = View.VISIBLE
    }

    private fun showOptionButtons(options: List<String>) {
        hideKeyboard()

        inputLayout.visibility = View.GONE
        hideKeyboard()

        val optionLayout = GridLayout(this).apply {
            rowCount = (options.size + 1) / 2
            columnCount = 2
            setPadding(16, 16, 16, 16)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            }
        }

        options.forEach { option ->
            val optionButton = createOptionButton(option) {
                userData[questions[currentQuestionIndex].question] = option
                addChatMessage(option, true)
                currentQuestionIndex++
                askNextQuestion()
                chatLayout.removeView(optionLayout)
            }

            optionButton.background = ContextCompat.getDrawable(this, R.drawable.option_background)

            val layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(8, 8, 8, 8)
            }
            optionButton.layoutParams = layoutParams
            optionButton.gravity = Gravity.CENTER

            optionLayout.addView(optionButton)
        }

        chatLayout.addView(optionLayout)
        showInputField(InputType.TYPE_CLASS_TEXT);

    }



    private fun createOptionButton(text: String, onClickListener: View.OnClickListener): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener(onClickListener)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
        }
    }

    private fun addChatMessage(message: String, isUserMessage: Boolean = false) {
        val messageView = TextView(this).apply {
            text = message
            textSize = 16f

            gravity= Gravity.CENTER_VERTICAL

            setBackgroundResource(if (isUserMessage) R.drawable.user_message_background else R.drawable.bot_message_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = if (isUserMessage) Gravity.END else Gravity.START

            }
            (layoutParams as LinearLayout.LayoutParams).setMargins(20,20,20,20)
        }
        chatLayout.addView(messageView)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.scrollTo(0,scrollView.bottom)
        }
    }
    private fun saveUserData() {
        val userDetails = mapOf(
            "age" to userData[questions[0].question]!!,
            "weight" to userData[questions[2].question]!!,
            "height" to userData[questions[1].question]!!,
            "gender" to userData[questions[3].question]!!,
            "meal_preferences" to userData[questions[4].question]!!,
            "exercise_preference" to userData[questions[5].question]!!,
            "fitness_goals" to userData[questions[6].question]!!,
            "sleep_schedule" to userData[questions[7].question]!!,
            "medical_problems" to userData[questions[8].question]!!
        )
        val heightInCm = convertHeightToCm(userData[questions[1].question]!!)
        val weightInKg = userData[questions[2].question]!!.toInt()
        val gender = if (userData[questions[3].question] == "Male") "m" else "f"
        val mealPreference = if (userData[questions[4].question] == "Veg") "veg" else "non-veg"
        val activityLevel = mapActivityLevel(userData[questions[5].question]!!)
        val goal = mapFitnessGoal(userData[questions[6].question]!!)

        val userData = UserHealthData(
            Height = heightInCm,
            Weight = weightInKg,
            Preference = mealPreference,
            Age = userData[questions[0].question]!!.toInt(),
            Activity = activityLevel,
            Sex = gender,
            Goal = goal
        )
        makeApiCall(userData)

        firebaseUtils.saveUserData(auth.currentUser!!.uid, userDetails)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    addChatMessage("Details saved!")
                    calculateBMI()
                } else {
                    addChatMessage("Error saving details. Please try again.")
                }
            }
    }

    private fun makeApiCall(userData: UserHealthData) {

//
//        val client = OkHttpClient()
//
//        val json = JSONObject() // Create a JSONObject
//        json.put("Height", userData.Height)
//        json.put("Weight", userData.Weight)
//        json.put("Preference", userData.Preference)
//        json.put("Age", userData.Age)
//        json.put("Activity", userData.Activity)
//        json.put("Sex", userData.Sex)
//        json.put("Goal", userData.Goal)
//
//        val mediaType = "application/json".toMediaType()
//        val body = RequestBody.create(mediaType, json.toString())
//
//        val request = Request.Builder()
//            .url("https://server-py-5l3l.onrender.com/generate_diet_plan")
//            .post(body)
//            .addHeader("Accept", "*/*")
//            .addHeader("Content-Type", "application/json")
//            .build()
//
//        CoroutineScope(IO).launch {
//            try {
//                val response = client.newCall(request).execute()
//
//                if (response.isSuccessful) {
//                    val responseBody = response.body!!
//
//
//                } else {
//                    Log.e("API Response", "API call unsuccessful: ${response.message}")
//                    // Handle failed response here
//                }
//            } catch (e: Exception) {
//                Log.e("API Response", "API call failed: ${e.message}")
//                // Handle exceptions here
//            } finally {
////                response.close() // Close the response body to release resources
//            }
//        }


        val queue = Volley.newRequestQueue(this) // Replace 'context' with your appropriate context

        val url = "https://server-py-5l3l.onrender.com/generate_diet_plan"

        val jsonObject = JSONObject()
        jsonObject.put("Height", userData.Height)
        jsonObject.put("Weight", userData.Weight)
        jsonObject.put("Preference", userData.Preference)
        jsonObject.put("Age", userData.Age)
        jsonObject.put("Activity", userData.Activity)
        jsonObject.put("Sex", userData.Sex)
        jsonObject.put("Goal", userData.Goal)

        val jsonObjectRequest = object : JsonObjectRequest(
            Method.POST, url, jsonObject,
            Response.Listener { response ->

                val responseString = response.toString()
                Log.d("API Response", "Response: $responseString")

                // Parse the response string using Gson (replace with your parsing logic)
                val gson = Gson()
                val dietPlan: DietPlan? = gson.fromJson(responseString, DietPlan::class.java)

                // Check for successful parsing and handle the DietPlan object
                if (dietPlan != null) {
                    // Use the parsed dietPlan object here (e.g., save data)
                } else {
                    Log.e("API Response", "Error parsing response JSON")
                }

            },
            Response.ErrorListener { error ->
                Log.e("API Response", "API call failed: ${error.message}")
            }) {


            override fun getBodyContentType(): String {
                return "application/json"
            }
        }

        queue.add(jsonObjectRequest)


    }




    private fun convertHeightToCm(height: String): Int {
        val parts = height.split(" ")
        Log.d("parts", parts.toString())
        if (parts.size == 2) {
            return parts[0].toInt()
        } else if (parts.size == 4) {
            val feet = parts[0].toInt()
            val inches = parts[2].toInt()
            return (feet * 30.48 + inches * 2.54).toInt()
        } else {
            throw IllegalArgumentException("Invalid height format")
        }
    }


    private fun mapActivityLevel(activity: String): Double {
        return when (activity) {
            "Basal Metabolic Rate (BMR)" -> 1.0
            "Sedentary: little or no exercise" -> 1.2
            "Light: exercise 1-3 times/week" -> 1.375
            "Moderate: exercise 4-5 times/week" -> 1.465
            "Active: daily exercise or intense exercise 3-4 times/week" -> 1.55
            "Very Active: intense exercise 6-7 times/week" -> 1.725
            "Extra Active: very intense exercise daily, or physical job" -> 1.9
            else -> 1.0
        }
    }

    private fun mapFitnessGoal(goal: String): String {
        return when (goal) {
            "Maintain weight" -> "m"
            "Mild weight loss of 0.5 lb (0.25 kg) per week" -> "l"
            "Weight loss of 1 lb (0.5 kg) per week" -> "l1"
            "Extreme weight loss of 2 lb (1 kg) per week" -> "l2"
            "Mild weight gain of 0.5 lb (0.25 kg) per week" -> "g"
            "Weight gain of 1 lb (0.5 kg) per week" -> "g1"
            "Extreme weight gain of 2 lb (1 kg) per week" -> "g2"
            else -> "m"
        }
    }
    private fun askHeightPreference() {
        inputLayout.visibility = View.GONE

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val feetButton = createButton("Feet/Inches") {
            chatLayout.removeView(buttonLayout)
            addHeightInputFields()
        }

        val cmButton = createButton("Centimeters") {
            chatLayout.removeView(buttonLayout)
            addCmInputField()
        }

        feetButton.background = ContextCompat.getDrawable(this, R.drawable.option_background)
        cmButton.background = ContextCompat.getDrawable(this, R.drawable.option_background)

        // Set layout parameters for buttons to occupy 50% width each with space in between
        val buttonLayoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = 1f
            gravity = Gravity.CENTER
            marginStart = resources.getDimensionPixelSize(R.dimen.button_margin)
            marginEnd = resources.getDimensionPixelSize(R.dimen.button_margin)
        }
        feetButton.layoutParams = buttonLayoutParams
        cmButton.layoutParams = buttonLayoutParams

        buttonLayout.addView(feetButton)
        buttonLayout.addView(cmButton)
        chatLayout.addView(buttonLayout)
    }



    private fun addHeightInputFields() {
        val inputFeetField = NumberPicker(this).apply {
            minValue = 0
            maxValue = 8
        }
        val inputInchesField = NumberPicker(this).apply {
            minValue = 0
            maxValue = 11
        }
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val sendButton = createButton("Send") {
            val feet = inputFeetField.value
            val inches = inputInchesField.value

            if (feet != 0 || inches != 0) {
                val height = "$feet feet $inches inches"
                userData[questions[currentQuestionIndex].question] = height
                chatLayout.removeView(buttonLayout)
                addChatMessage(height, true)
                currentQuestionIndex++
                askNextQuestion()
            } else {
                addChatMessage("Please provide a valid answer.")
            }
        }
        buttonLayout.addView(inputFeetField)
        buttonLayout.addView(inputInchesField)
        buttonLayout.addView(sendButton)
        chatLayout.addView(buttonLayout)
    }

    private fun addCmInputField() {
        val inputCmField = NumberPicker(this).apply {
            minValue = 0
            maxValue = 300
        }
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val sendButton = createButton("Send") {
            val cm = inputCmField.value

            if (cm != 0) {
                val height = "$cm cm"
                userData[questions[currentQuestionIndex].question] = height
                chatLayout.removeView(buttonLayout)
                addChatMessage(height, true)
                currentQuestionIndex++
                askNextQuestion()
            } else {
                addChatMessage("Please provide a valid answer.")
            }
        }
        buttonLayout.addView(inputCmField)
        buttonLayout.addView(sendButton)
        chatLayout.addView(buttonLayout)

    }
    private fun createButton(text: String, onClickListener: View.OnClickListener): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener(onClickListener)
        }
    }


    private fun calculateBMI() {
        val weight = userData["What is your weight?"]?.toDoubleOrNull()
        val heightStr = userData["What is your height?"]
        val heightInCm = when {
            heightStr?.contains("cm") == true -> heightStr.split(" ")[0].toDoubleOrNull()
            else -> {
                val (feet, inches) = heightStr!!.split(" ").filter { it.matches(Regex("\\d+")) }
                    .map { it.toDouble() }
                (feet * 30.48) + (inches * 2.54)
            }
        }

        if (weight != null && heightInCm != null) {
            val heightInMeters = heightInCm / 100
            val bmi = weight / (heightInMeters * heightInMeters)
            addChatMessage("Your BMI is %.2f".format(bmi))
        } else {
            addChatMessage("Error calculating BMI. Please ensure weight and height are entered correctly.")
        }
    }


    fun Activity.hideKeyboard() {
        val view: View? = this.currentFocus
        view?.let { v ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }


    private fun handleUserInput(userInput: String) {
        when {
            userInput.contains("breakfast", ignoreCase = true) -> showMealInput()
            userInput.contains("lunch", ignoreCase = true) -> showMealInput()
            userInput.contains("dinner", ignoreCase = true) -> showMealInput()
            userInput.contains("meal", ignoreCase = true) -> showMealInput()
            userInput.contains("exercise", ignoreCase = true) && userInput.contains("add", ignoreCase = true) -> showExerciseInput()
            userInput.contains("do exercise", ignoreCase = true) || userInput.contains("go to exercise", ignoreCase = true) || userInput.contains("want", ignoreCase = true) -> navigateToExerciseFragment()
            userInput.contains("weight", ignoreCase = true) -> openWeightEditor()
            userInput.contains("blood sugar", ignoreCase = true) -> openGlucoseEditor()
            userInput.contains("diet plan", ignoreCase = true) -> navigateToDietPlanFragment()
            userInput.contains("main", ignoreCase = true) -> navigateToMainActivity()
            userInput.contains("posture", ignoreCase = true) -> navigateToPostureActivity()
            else -> handleFallback(userInput)
        }
    }


    private fun showMealInput() {
        mealList.add(MealAdd())

        val mealInputLayout = layoutInflater.inflate(R.layout.meal_input_layout, null)
        val mealRecyclerView: RecyclerView = mealInputLayout.findViewById(R.id.meal_recycler_view)
        val addMealButton: Button = mealInputLayout.findViewById(R.id.iv_add_meal)
        val saveMealButton: Button = mealInputLayout.findViewById(R.id.saveBtn)

        mealRecyclerView.layoutManager = LinearLayoutManager(this)
        val mealAddAdapter = MealAddAdapter(mealList, object : MealAddAdapter.OnDeleteClickListener {
            override fun onDeleteClick(position: Int, type: String) {
                mealList.removeAt(position)
                mealAddAdapter.notifyItemRemoved(position)
            }
        })
        mealRecyclerView.adapter = mealAddAdapter

        addMealButton.setOnClickListener {
            mealList.add(MealAdd())
            mealAddAdapter.notifyItemInserted(mealList.size - 1)
        }

        saveMealButton.setOnClickListener {
            calculateMealCalories()
            findViewById<LinearLayout>(R.id.chatLayout).removeView(mealInputLayout)
            addChatMessage("Successfully added Meal")
        }

        findViewById<LinearLayout>(R.id.chatLayout).addView(mealInputLayout)
    }

    private fun calculateMealCalories() {
        val meals = mealList.map { Item(it.dishName, it.quantity) }
        lifecycleScope.launch {
            val responseMeal =  apiService.calculateCalories(meals)

            if (responseMeal.isSuccessful) {
                val totalCalories = responseMeal.body()?.total_calories ?: 0
                firebaseUtils.addOrUpdateHealthData(
                    null, mealList, null, totalCalories, null, null, null,
                    onSuccess = { },
                    onFailure = { e ->  Log.e("Error", e.message.toString()) }
                )
            }
        }
    }

    private fun showExerciseInput() {
        exerciseList.add(ExerciseAdd())

        val exerciseInputLayout = layoutInflater.inflate(R.layout.exercise_input_layout, null)
        val exerciseRecyclerView: RecyclerView = exerciseInputLayout.findViewById(R.id.exercise_recycler_view)
        val addExerciseButton: Button = exerciseInputLayout.findViewById(R.id.iv_add_exercise)
        val saveExerciseButton: Button = exerciseInputLayout.findViewById(R.id.saveBtn)

        exerciseRecyclerView.layoutManager = LinearLayoutManager(this)
        val exerciseAddAdapter = ExerciseAddAdapter(exerciseList, object : ExerciseAddAdapter.OnDeleteClickListener {
            override fun onDeleteClick(position: Int, type: String) {
                exerciseList.removeAt(position)
                exerciseAddAdapter.notifyItemRemoved(position)
            }
        })
        exerciseRecyclerView.adapter = exerciseAddAdapter

        addExerciseButton.setOnClickListener {
            exerciseList.add(ExerciseAdd())
            exerciseAddAdapter.notifyItemInserted(exerciseList.size - 1)
        }

        saveExerciseButton.setOnClickListener {
            saveExerciseData()
            findViewById<LinearLayout>(R.id.chatLayout).removeView(exerciseInputLayout)
            addChatMessage("Successfully added Exercise")
        }

        findViewById<LinearLayout>(R.id.chatLayout).addView(exerciseInputLayout)
    }

    private fun saveExerciseData() {
        val exercises = exerciseList.map { exerItem(it.exerciseName, it.time) }
        lifecycleScope.launch {
            val responseExercise =  apiService.calculateCaloriesBurn(exercises)

            if (responseExercise.isSuccessful) {
                val totalCalories = responseExercise.body()?.total_calorie_burn ?: 0
                firebaseUtils.addOrUpdateHealthData(
                    exerciseList, null, null, null, totalCalories, null, null,
                    onSuccess = { },
                    onFailure = { e ->  Log.e("Error", e.message.toString()) }
                )
            }
        }
    }

    private fun navigateToDietPlanFragment() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("SHOW_DIET_PLAN_FRAGMENT", true)
        startActivity(intent)
    }
    private fun navigateToPostureActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("POSTURE_FRAGMENT", true)
        startActivity(intent)
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
    }


    private fun navigateToExerciseFragment() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("EXERCISE_FRAGMENT", true)
        startActivity(intent)
    }

    private fun openWeightEditor() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogLayout = inflater.inflate(R.layout.dialog_edit_weight, null)
        val editText = dialogLayout.findViewById<EditText>(R.id.editTextWeight)

        with(builder) {
            setTitle("Edit Weight")
            setView(dialogLayout)
            setPositiveButton("Save") { _, _ ->
                val weight = editText.text.toString().toFloat()

                if (weight!=0.0f) {
                    firebaseUtils.addOrUpdateHealthData(emptyList(), emptyList(), 0, 0, 0,weight, 0.0f,
                        onSuccess = {
                            showToast("Weight Level Successully")
                        }, onFailure = {
                            showToast("Error in adding Weight")
                        })

                } else {
                    showToast( "Weight cannot be empty")
                }
            }
            setNegativeButton("Cancel") { dialog, which ->
                dialog.dismiss()
            }
            show()
        }
    }

    private fun openGlucoseEditor() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogLayout = inflater.inflate(R.layout.dialog_edit_glucose, null)
        val editText = dialogLayout.findViewById<EditText>(R.id.editTextGlucose)

        with(builder) {
            setTitle("Edit Glucose Level")
            setView(dialogLayout)
            setPositiveButton("Save") { _, _ ->
                val glucose = editText.text.toString().toFloat()
                if (glucose!=0.0f) {
                    firebaseUtils.addOrUpdateHealthData(emptyList(), emptyList(), 0, 0, 0,0.0f, glucose , onSuccess = {
                        showToast("Glucose Level Added Successully")
                    }, onFailure = {
                        showToast("Error adding glucose level")
                    })
                } else {
                    showToast("Glucose level cannot be empty")
                }
            }
            setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            show()
        }
    }



    private fun handleFallback(userInput: String) {

    }


    private fun showToast(s: String) {
        Toast.makeText(this, s , Toast.LENGTH_SHORT).show()

    }



}
