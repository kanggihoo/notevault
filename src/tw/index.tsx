/**
 * className 을 지원하는 CSS 래퍼 컴포넌트.
 *
 * NativeWind v5(react-native-css)는 v4 와 달리 RN 컴포넌트에 자동으로
 * className 을 붙이지 않는다 (metro 의 globalClassNamePolyfill: false).
 * useCssElement 로 감싼 이 래퍼들을 통해서만 Tailwind 클래스를 쓴다.
 * expo-tailwind-setup skill 의 패턴이다.
 */
import { useCssElement } from 'react-native-css'
import { Link as RouterLink } from 'expo-router'
import React from 'react'
import {
  View as RNView,
  Text as RNText,
  Pressable as RNPressable,
  ScrollView as RNScrollView,
  TextInput as RNTextInput,
} from 'react-native'

type WithClassName<C extends React.ComponentType<any>> = React.ComponentProps<C> & {
  className?: string
}

export const View = (props: WithClassName<typeof RNView>) =>
  useCssElement(RNView, props, { className: 'style' })
View.displayName = 'CSS(View)'

export const Text = (props: WithClassName<typeof RNText>) =>
  useCssElement(RNText, props, { className: 'style' })
Text.displayName = 'CSS(Text)'

// Pressable·ScrollView 는 props 유니언이 useCssElement 제네릭과 곱해지며
// TS2590(표현 불가 유니언)이 나므로 내부 전달만 캐스팅한다. 외부 타입은 유지.
export const Pressable = (props: WithClassName<typeof RNPressable>) =>
  useCssElement(RNPressable as React.ComponentType<any>, props, { className: 'style' })
Pressable.displayName = 'CSS(Pressable)'

export const ScrollView = (
  props: WithClassName<typeof RNScrollView> & { contentContainerClassName?: string },
) =>
  useCssElement(RNScrollView as React.ComponentType<any>, props, {
    className: 'style',
    contentContainerClassName: 'contentContainerStyle',
  })
ScrollView.displayName = 'CSS(ScrollView)'

export const TextInput = (props: WithClassName<typeof RNTextInput>) =>
  useCssElement(RNTextInput, props, { className: 'style' })
TextInput.displayName = 'CSS(TextInput)'

export const Link = (props: React.ComponentProps<typeof RouterLink> & { className?: string }) =>
  useCssElement(RouterLink as React.ComponentType<any>, props, { className: 'style' })
