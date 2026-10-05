package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 「已保存密码」的占位符：只在界面上表示「本机已经存了密码，可以直接登录」。
 *
 * 它**永远不参与真实密码** —— 密码输入框的值要么是用户真正输入的内容，要么显示这个占位符。
 */
const val SAVED_PASSWORD_PLACEHOLDER = "••••••••"

/** 占位符字符本身：占位符态下用户编辑时，需要把它从输入里剔除。 */
private const val PLACEHOLDER_CHAR = '•'

/**
 * 密码输入框的草稿。
 *
 * [text] 只放**用户真实输入**的内容；已保存的密码用占位符显示，不进 [text]。
 *
 * 这样处理的原因是字符串裁剪并不可靠：以前把占位符直接塞进 `value`，再用
 * `startsWith/endsWith/contains("••••••••")` 去猜有没有被改过 —— 用户按一次退格会得到 7 个「•」，
 * 三个分支全都匹配不上，于是「•••••••」被当成真密码提交（视觉上还是「8 个点」，用户完全看不出来）。
 * 现在占位符只是一个显示态，任何编辑（退格、全选删除、粘贴）都只会得到真实的输入内容。
 */
class PasswordDraft {

    var text by mutableStateOf("")

    /** 用户是否已经动过这个输入框（动过就不再显示已保存密码的占位符）。 */
    var edited by mutableStateOf(false)
}

/**
 * 带「已保存密码」占位符的密码输入框。登录 Sheet 与各处 WebVPN 密码弹窗共用同一份行为。
 *
 * @param hasSavedPassword 该槽位是否有可用的已保存密码（决定是否显示占位符，**不**决定提交时用哪个值）
 */
@Composable
fun SavedPasswordField(
    draft: PasswordDraft,
    hasSavedPassword: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Password,
    visualTransformation: VisualTransformation = PasswordVisualTransformation(),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    shape: RoundedCornerShape = RoundedCornerShape(16.dp)
) {
    val showPlaceholder = hasSavedPassword && !draft.edited
    OutlinedTextField(
        value = if (showPlaceholder) SAVED_PASSWORD_PLACEHOLDER else draft.text,
        onValueChange = { newValue ->
            // 占位符态下的任何编辑都视为「重新输入」：把它从输入里剔除，得到的才是用户真正打的内容
            draft.text = if (showPlaceholder) newValue.replace(PLACEHOLDER_CHAR.toString(), "") else newValue
            draft.edited = true
        },
        label = { Text(label) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText?.takeIf { it.isNotBlank() }?.let { hint -> { Text(text = hint) } },
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        shape = shape,
        colors = colors,
        enabled = enabled
    )
}

/** 登录 Sheet 里的密码框配色（比默认值更贴近弹窗底色）。 */
@Composable
fun savedPasswordFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
)
