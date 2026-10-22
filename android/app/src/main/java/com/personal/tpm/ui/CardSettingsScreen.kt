package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun CardSettingsScreen(onBack: () -> Unit) {
    CardLayoutEditor(onBack)
}

@Composable
fun ManagementScreen(onBack: () -> Unit, openOrder: (String) -> Unit) { PersonnelManagementScreen(onBack, openOrder) }
