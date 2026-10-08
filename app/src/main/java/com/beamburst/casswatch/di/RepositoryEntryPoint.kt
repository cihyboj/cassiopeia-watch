// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.beamburst.casswatch.data.repository.GShockRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
fun interface RepositoryEntryPoint {
    fun getGShockRepository(): GShockRepository
}