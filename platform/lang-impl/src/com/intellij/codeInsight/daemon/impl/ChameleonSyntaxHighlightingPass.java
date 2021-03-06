/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeHighlighting.MainHighlightingPassFactory;
import com.intellij.codeHighlighting.Pass;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.lang.Language;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.ILazyParseableElementType;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.TreeTraversal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.intellij.psi.SyntaxTraverser.psiTraverser;

class ChameleonSyntaxHighlightingPass extends GeneralHighlightingPass {

  public static class Factory extends AbstractProjectComponent implements MainHighlightingPassFactory {

    protected Factory(Project project, TextEditorHighlightingPassRegistrar registrar) {
      super(project);
      registrar.registerTextEditorHighlightingPass(this, null, new int[]{Pass.UPDATE_ALL}, false, -1);
    }

    @Nullable
    @Override
    public TextEditorHighlightingPass createHighlightingPass(@NotNull PsiFile file, @NotNull Editor editor) {
      TextRange restrict = FileStatusMap.getDirtyTextRange(editor, Pass.UPDATE_ALL);
      if (restrict == null) return new ProgressableTextEditorHighlightingPass.EmptyPass(myProject, editor.getDocument());
      ProperTextRange priority = VisibleHighlightingPassFactory.calculateVisibleRange(editor);
      return new ChameleonSyntaxHighlightingPass(myProject, file, editor.getDocument(), ProperTextRange.create(restrict),
                                                 priority, editor, new DefaultHighlightInfoProcessor());
    }

    @Nullable
    @Override
    public TextEditorHighlightingPass createMainHighlightingPass(@NotNull PsiFile file,
                                                                 @NotNull Document document,
                                                                 @NotNull HighlightInfoProcessor highlightInfoProcessor) {
      ProperTextRange range = ProperTextRange.from(0, document.getTextLength());
      return new ChameleonSyntaxHighlightingPass(myProject, file, document, range, range, null, highlightInfoProcessor);
    }
  }

  ChameleonSyntaxHighlightingPass(@NotNull Project project,
                                  @NotNull PsiFile file,
                                  @NotNull Document document,
                                  @NotNull ProperTextRange restrictRange,
                                  @NotNull ProperTextRange priorityRange,
                                  @Nullable Editor editor,
                                  @NotNull HighlightInfoProcessor highlightInfoProcessor) {
    super(project, file, document, restrictRange.getStartOffset(), restrictRange.getEndOffset(), true, priorityRange, editor,
          highlightInfoProcessor);
  }

  @Override
  public void collectInformationWithProgress(@NotNull ProgressIndicator progress) {
    SyntaxTraverser<PsiElement> s = psiTraverser(myFile)
      .filter(o -> {
        IElementType type = PsiUtilCore.getElementType(o);
        return type instanceof ILazyParseableElementType && !(type instanceof IFileElementType);
      });

    List<PsiElement> lazyOutside = ContainerUtil.newArrayListWithCapacity(100);
    List<PsiElement> lazyInside = ContainerUtil.newArrayListWithCapacity(100);
    List<HighlightInfo> outside = ContainerUtil.newArrayListWithCapacity(100);
    List<HighlightInfo> inside = ContainerUtil.newArrayListWithCapacity(100);

    for (PsiElement e : s) {
      (e.getTextRange().intersects(myPriorityRange) ? lazyInside : lazyOutside).add(e);
    }
    for (PsiElement e : lazyInside) {
      collectHighlights(e, inside, outside, myPriorityRange);
    }
    myHighlightInfoProcessor.highlightsInsideVisiblePartAreProduced(myHighlightingSession, getEditor(), inside, myPriorityRange, myRestrictRange, getId());
    for (PsiElement e : lazyOutside) {
      collectHighlights(e, inside, outside, myPriorityRange);
    }
    myHighlightInfoProcessor.highlightsOutsideVisiblePartAreProduced(myHighlightingSession, getEditor(), outside, myPriorityRange, myRestrictRange, getId());
    myHighlights.addAll(inside);
    myHighlights.addAll(outside);
  }

  private void collectHighlights(@NotNull PsiElement element,
                                 @NotNull List<HighlightInfo> inside,
                                 @NotNull List<HighlightInfo> outside,
                                 @NotNull ProperTextRange priorityRange) {
    EditorColorsScheme scheme = ObjectUtils.notNull(getColorsScheme(), EditorColorsManager.getInstance().getGlobalScheme());
    TextAttributes defaultAttrs = scheme.getAttributes(HighlighterColors.TEXT);

    Language language = ILazyParseableElementType.LANGUAGE_KEY.get(element.getNode());
    if (language == null) return;

    SyntaxHighlighter syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, myProject, myFile.getVirtualFile());
    for (PsiElement token : psiTraverser(element).traverse(TreeTraversal.LEAVES_DFS)) {
      TextRange tr = token.getTextRange();
      if (tr.isEmpty()) continue;
      IElementType type = PsiUtilCore.getElementType(token);
      TextAttributesKey[] keys = syntaxHighlighter.getTokenHighlights(type);

      // force attribute colors to override host' ones
      TextAttributes attributes = null;
      for (TextAttributesKey key : keys) {
        TextAttributes attrs2 = scheme.getAttributes(key);
        if (attrs2 != null) {
          attributes = attributes == null ? attrs2 : TextAttributes.merge(attributes, attrs2);
        }
      }
      TextAttributes forcedAttributes;
      if (attributes == null || attributes.isEmpty() || attributes.equals(defaultAttrs)) {
        forcedAttributes = TextAttributes.ERASE_MARKER;
      }
      else {
        HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.INJECTED_LANGUAGE_FRAGMENT).
          range(tr).
          textAttributes(TextAttributes.ERASE_MARKER).
          createUnconditionally();
        (priorityRange.contains(tr) ? inside : outside).add(info);

        forcedAttributes = new TextAttributes(attributes.getForegroundColor(), attributes.getBackgroundColor(),
                                              attributes.getEffectColor(), attributes.getEffectType(), attributes.getFontType());
      }

      HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.INJECTED_LANGUAGE_FRAGMENT).
        range(tr).
        textAttributes(forcedAttributes).
        createUnconditionally();
      (priorityRange.contains(tr) ? inside : outside).add(info);
    }
  }

  @Override
  protected void applyInformationWithProgress() {
  }
}
