package com.nimbo.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nimbo.R

// M3 scale: 4/8/12/16/28. Pill = CircleShape via defaults.
val NimboShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// Roboto Flex variable (design-system.html). Un archivo cubre wght 100..1000.
private val RobotoFlex = FontFamily(Font(R.font.robotoflex))

private val Default = Typography()
val NimboTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontFamily = RobotoFlex),
    displayMedium = Default.displayMedium.copy(fontFamily = RobotoFlex),
    displaySmall = Default.displaySmall.copy(fontFamily = RobotoFlex),
    headlineLarge = Default.headlineLarge.copy(fontFamily = RobotoFlex),
    headlineMedium = Default.headlineMedium.copy(fontFamily = RobotoFlex),
    headlineSmall = Default.headlineSmall.copy(fontFamily = RobotoFlex),
    titleLarge = Default.titleLarge.copy(fontFamily = RobotoFlex),
    titleMedium = Default.titleMedium.copy(fontFamily = RobotoFlex),
    titleSmall = Default.titleSmall.copy(fontFamily = RobotoFlex),
    bodyLarge = Default.bodyLarge.copy(fontFamily = RobotoFlex),
    bodyMedium = Default.bodyMedium.copy(fontFamily = RobotoFlex),
    bodySmall = Default.bodySmall.copy(fontFamily = RobotoFlex),
    labelLarge = Default.labelLarge.copy(fontFamily = RobotoFlex),
    labelMedium = Default.labelMedium.copy(fontFamily = RobotoFlex),
    labelSmall = Default.labelSmall.copy(fontFamily = RobotoFlex),
)
